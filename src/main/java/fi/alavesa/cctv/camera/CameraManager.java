package fi.alavesa.cctv.camera;

import fi.alavesa.cctv.access.PermissionClearanceProvider;
import fi.alavesa.cctv.api.CameraState;
import fi.alavesa.cctv.api.CameraType;
import fi.alavesa.cctv.api.CameraView;
import fi.alavesa.cctv.api.CctvApi;
import fi.alavesa.cctv.api.ClearanceProvider;
import fi.alavesa.cctv.api.Mount;
import fi.alavesa.cctv.api.event.CameraCreateEvent;
import fi.alavesa.cctv.api.event.CameraDamageEvent;
import fi.alavesa.cctv.api.event.CameraDisableEvent;
import fi.alavesa.cctv.api.event.CameraEnableEvent;
import fi.alavesa.cctv.api.event.CameraRemoveEvent;
import fi.alavesa.cctv.api.event.CameraRepairEvent;
import fi.alavesa.cctv.config.CctvConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * The camera registry and the implementation of {@link CctvApi}. Owns the records, the chunk index, persistence and
 * the state machine (every state change goes through {@link #change}). Main thread only.
 */
public final class CameraManager implements CctvApi {

    private final Plugin plugin;
    private final Logger log;
    private final Keys keys;
    private final CameraStore store;
    private final CameraParts parts;
    private final BbCameras bb;
    private final CameraItems items;
    private final PtzMotor motor;
    private final Map<String, Camera> byId = new LinkedHashMap<>();
    /** world name -> chunk key -> cameras whose lens is in that chunk */
    private final Map<String, Map<Long, List<Camera>>> chunks = new HashMap<>();
    private CctvConfig cfg;
    private ClearanceProvider clearance = new PermissionClearanceProvider(10);
    private @Nullable ViewBridge views;
    private int nextId = 1;
    private boolean dirty;

    public CameraManager(Plugin plugin, CctvConfig cfg) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.cfg = cfg;
        this.keys = new Keys(plugin);
        this.store = new CameraStore(plugin.getDataFolder().toPath().resolve("cameras.yml"), log);
        this.bb = new BbCameras(plugin.getDataFolder(), log);
        this.parts = new CameraParts(keys, cfg, bb);
        this.items = new CameraItems(keys, cfg);
        this.motor = new PtzMotor(this, parts);
    }

    // ------------------------------------------------------------------ lifecycle

    public void load() {
        bb.load(cfg.types.values());
        CameraStore.Loaded loaded = store.load(this::resolveType, cfg.damagedThreshold);
        for (Camera c : loaded.cameras()) {
            byId.put(c.id, c);
            index(c);
        }
        nextId = loaded.nextId();
        for (Camera c : byId.values()) parts.spawn(c);
        motor.start(plugin, cfg.ptzUpdateTicks);
        Bukkit.getScheduler().runTaskTimer(plugin, this::autosave, cfg.autosaveSeconds * 20L, cfg.autosaveSeconds * 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::animateModels, 2, 2);
        log.info("Loaded " + byId.size() + " camera(s)");
    }

    public void shutdown() {
        motor.stop();
        for (Camera c : byId.values()) {
            // land moving cameras on their target so the saved angle matches what players last saw
            c.pan = c.targetPan;
            c.tilt = c.targetTilt;
            parts.despawn(c);
        }
        store.saveNowAndClose(store.serialise(byId.values(), nextId));
    }

    /** /cctv reload: new config, re-resolve types, respawn every loaded camera with the new models. */
    public void reload(CctvConfig cfg) {
        this.cfg = cfg;
        parts.config(cfg);
        items.config(cfg);
        motor.restart(plugin, cfg.ptzUpdateTicks);
        for (Camera c : byId.values()) parts.despawn(c);
        bb.load(cfg.types.values());
        for (Camera c : byId.values()) {
            parts.despawn(c);
            CameraType before = c.type;
            c.type = resolveType(c.typeKey);
            c.damagedThreshold = cfg.damagedThreshold;
            if (before.maxHealth() != c.type.maxHealth()) c.health = Math.min(c.health, c.type.maxHealth());
            parts.spawn(c);
        }
    }

    /** Plays .bbmodel animation clips on cameras that someone is near enough to see (every 2 ticks). */
    private void animateModels() {
        for (Camera c : byId.values()) {
            if (c.bbState == null || c.bbParts == null) continue;
            World w = c.bukkitWorld();
            if (w == null) continue;
            boolean seen = false;
            for (Player p : w.getPlayers()) {
                double dx = p.getX() - c.x, dy = p.getY() - c.y, dz = p.getZ() - c.z;
                if (dx * dx + dy * dy + dz * dz < 48 * 48) {
                    seen = true;
                    break;
                }
            }
            if (!seen) continue;
            BbCameras.Model mdl = bb.model(c.type);
            if (mdl == null) continue;
            BbCameras.animate(mdl, c, c.bbState, 2);
            parts.updateBb(c, 2);
        }
    }

    /** Item models and textures of the loaded .bbmodel cameras, for the resource pack. */
    public java.util.Map<String, byte[]> modelPackFiles() {
        return bb.packFiles();
    }

    private void autosave() {
        if (!dirty) return;
        dirty = false;
        store.saveAsync(store.serialise(byId.values(), nextId));
    }

    void markDirty() {
        dirty = true;
    }

    public CctvConfig config() {
        return cfg;
    }

    public CameraItems items() {
        return items;
    }

    public Keys keys() {
        return keys;
    }

    // ------------------------------------------------------------------ chunk handling

    public void onChunkLoad(Chunk chunk) {
        for (Camera c : inChunk(chunk)) parts.spawn(c);
    }

    public void onChunkUnload(Chunk chunk) {
        for (Camera c : inChunk(chunk)) parts.despawn(c);
    }

    /** True if the entity is a current part of a registered camera. Strays (old builds, crashes) return false. */
    public boolean isLivePart(Entity e) {
        String id = parts.cameraIdOf(e);
        if (id == null) return false;
        Camera c = byId.get(id);
        return c != null && parts.owns(e.getUniqueId(), c);
    }

    public boolean isTagged(Entity e) {
        return parts.cameraIdOf(e) != null;
    }

    /** The camera an entity belongs to, if it is one of our live parts. */
    public @Nullable Camera cameraOf(Entity e) {
        String id = parts.cameraIdOf(e);
        if (id == null) return null;
        Camera c = byId.get(id);
        return c != null && parts.owns(e.getUniqueId(), c) ? c : null;
    }

    private List<Camera> inChunk(Chunk chunk) {
        Map<Long, List<Camera>> w = chunks.get(chunk.getWorld().getName());
        if (w == null) return List.of();
        List<Camera> l = w.get(Chunk.getChunkKey(chunk.getX(), chunk.getZ()));
        return l == null ? List.of() : List.copyOf(l);
    }

    private void index(Camera c) {
        chunks.computeIfAbsent(c.world, k -> new HashMap<>())
                .computeIfAbsent(Chunk.getChunkKey(c.chunkX(), c.chunkZ()), k -> new ArrayList<>(2))
                .add(c);
    }

    private void unindex(Camera c) {
        Map<Long, List<Camera>> w = chunks.get(c.world);
        if (w == null) return;
        long key = Chunk.getChunkKey(c.chunkX(), c.chunkZ());
        List<Camera> l = w.get(key);
        if (l != null) {
            l.remove(c);
            if (l.isEmpty()) w.remove(key);
        }
    }

    // ------------------------------------------------------------------ lookups

    @Override
    public Collection<Camera> cameras() {
        return Collections.unmodifiableCollection(byId.values());
    }

    @Override
    public Optional<CameraView> camera(String id) {
        return Optional.ofNullable(byId.get(id.toLowerCase(Locale.ROOT)));
    }

    public @Nullable Camera get(String id) {
        return byId.get(id.toLowerCase(Locale.ROOT));
    }

    @Override
    public List<CameraView> camerasInGroup(String group) {
        List<CameraView> out = new ArrayList<>();
        for (Camera c : byId.values()) if (c.group.equalsIgnoreCase(group)) out.add(c);
        return out;
    }

    @Override
    public Set<String> groups() {
        Set<String> g = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Camera c : byId.values()) g.add(c.group);
        return g;
    }

    @Override
    public Map<String, CameraType> types() {
        return cfg.types;
    }

    /** Nearest camera whose lens is within {@code radius} of {@code at}. */
    public @Nullable Camera nearest(Location at, double radius) {
        World w = at.getWorld();
        if (w == null) return null;
        Camera best = null;
        double bestD = radius * radius;
        for (Camera c : byId.values()) {
            if (!c.world.equals(w.getName())) continue;
            double dx = c.x - at.getX(), dy = c.y - at.getY(), dz = c.z - at.getZ();
            double d = dx * dx + dy * dy + dz * dz;
            if (d <= bestD) {
                bestD = d;
                best = c;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ create / remove

    @Override
    public Optional<CameraView> create(String typeKey, Location eye, Mount mount, float baseYaw, float basePitch,
                                       @Nullable String name, @Nullable String group) {
        return create(typeKey, eye, mount, baseYaw, basePitch, name, group, null).map(c -> c);
    }

    public Optional<Camera> create(String typeKey, Location eye, Mount mount, float baseYaw, float basePitch,
                                   @Nullable String name, @Nullable String group, @Nullable Player creator) {
        CameraType type = cfg.types.get(typeKey.toLowerCase(Locale.ROOT));
        if (type == null) throw new IllegalArgumentException("unknown camera type: " + typeKey);
        World w = eye.getWorld();
        if (w == null) throw new IllegalArgumentException("location has no world");
        String id = "cam-" + nextId;
        while (byId.containsKey(id)) id = "cam-" + (++nextId);
        Camera c = new Camera(id, type.key(), type, w.getName(), eye.getX(), eye.getY(), eye.getZ(), mount,
                Orientation.wrap(baseYaw), Math.clamp(basePitch, -90f, 90f));
        c.damagedThreshold = cfg.damagedThreshold;
        c.name = name == null || name.isBlank() ? id : name;
        c.group = group == null || group.isBlank() ? "default" : group.toLowerCase(Locale.ROOT);
        CameraCreateEvent event = new CameraCreateEvent(c, creator);
        if (!event.callEvent()) return Optional.empty();
        nextId++;
        byId.put(c.id, c);
        index(c);
        parts.spawn(c);
        dirty = true;
        return Optional.of(c);
    }

    @Override
    public boolean remove(String id) {
        return remove(id, CameraRemoveEvent.Cause.API);
    }

    public boolean remove(String id, CameraRemoveEvent.Cause cause) {
        Camera c = byId.remove(id.toLowerCase(Locale.ROOT));
        if (c == null) return false;
        boolean wasViewable = c.state().viewable();
        motor.forget(c);
        parts.despawn(c);
        unindex(c);
        dirty = true;
        if (wasViewable) new CameraDisableEvent(c, CameraDisableEvent.Reason.REMOVED).callEvent();
        new CameraRemoveEvent(c, cause).callEvent();
        return true;
    }

    // ------------------------------------------------------------------ edits

    @Override
    public boolean rename(String id, String name) {
        Camera c = get(id);
        if (c == null || name.isBlank()) return false;
        c.name = name;
        dirty = true;
        return true;
    }

    @Override
    public boolean setGroup(String id, String group) {
        Camera c = get(id);
        if (c == null || group.isBlank()) return false;
        c.group = group.toLowerCase(Locale.ROOT);
        dirty = true;
        return true;
    }

    @Override
    public boolean setPowered(String id, boolean powered) {
        Camera c = get(id);
        if (c == null) return false;
        if (c.powered != powered) change(c, () -> c.powered = powered, CameraEnableEvent.Reason.POWER);
        return true;
    }

    @Override
    public int setGroupPowered(String group, boolean powered) {
        int n = 0;
        for (Camera c : List.copyOf(byId.values())) {
            if (c.group.equalsIgnoreCase(group) && c.powered != powered) {
                change(c, () -> c.powered = powered, CameraEnableEvent.Reason.POWER);
                n++;
            }
        }
        return n;
    }

    @Override
    public boolean setEnabled(String id, boolean enabled) {
        Camera c = get(id);
        if (c == null) return false;
        if (c.enabled != enabled) change(c, () -> c.enabled = enabled, CameraEnableEvent.Reason.ADMIN);
        return true;
    }

    public void setFov(Camera c, float fov) {
        c.fov = Math.clamp(fov, 10f, 140f);
        dirty = true;
    }

    public void setClearance(Camera c, int level) {
        c.clearance = Math.max(0, level);
        dirty = true;
    }

    /** Sets the pan/tilt limits and pulls the current angle and target inside them. */
    public void setLimits(Camera c, float panMin, float panMax, float tiltMin, float tiltMax) {
        c.panMin = Math.min(panMin, panMax);
        c.panMax = Math.max(panMin, panMax);
        c.tiltMin = Math.min(tiltMin, tiltMax);
        c.tiltMax = Math.max(tiltMin, tiltMax);
        aimRaw(c, c.targetPan, c.targetTilt);
        dirty = true;
    }

    public void setBase(Camera c, float yaw, float pitch) {
        c.baseYaw = Orientation.wrap(yaw);
        c.basePitch = Math.clamp(pitch, -90f, 90f);
        parts.despawn(c);
        parts.spawn(c);
        dirty = true;
    }

    public boolean setType(Camera c, String typeKey) {
        CameraType t = cfg.types.get(typeKey.toLowerCase(Locale.ROOT));
        if (t == null) return false;
        change(c, () -> {
            c.typeKey = t.key();
            c.type = t;
            c.health = Math.min(c.health, t.maxHealth());
        }, CameraEnableEvent.Reason.ADMIN);
        parts.despawn(c);
        parts.spawn(c);
        return true;
    }

    // ------------------------------------------------------------------ damage / repair

    @Override
    public double damage(String id, double amount, @Nullable Entity source) {
        Camera c = get(id);
        return c == null ? -1 : damage(c, amount, source, CameraDamageEvent.Cause.API);
    }

    public double damage(Camera c, double amount, @Nullable Entity source, CameraDamageEvent.Cause cause) {
        if (amount <= 0 || c.health <= 0) return c.health <= 0 ? 0 : -1;
        CameraDamageEvent event = new CameraDamageEvent(c, source, cause, amount);
        if (!event.callEvent() || event.getAmount() <= 0) return -1;
        change(c, () -> c.health = Math.max(0, c.health - event.getAmount()), CameraEnableEvent.Reason.REPAIRED);
        World w = c.bukkitWorld();
        if (w != null) {
            Location at = new Location(w, c.x, c.y, c.z);
            if (c.health <= 0) {
                w.spawnParticle(Particle.LARGE_SMOKE, at, 12, 0.15, 0.15, 0.15, 0.02);
                w.playSound(at, "minecraft:block.glass.break", SoundCategory.BLOCKS, 1f, 0.8f);
            } else {
                w.spawnParticle(Particle.ELECTRIC_SPARK, at, 6, 0.1, 0.1, 0.1, 0.05);
                w.playSound(at, "minecraft:block.chain.hit", SoundCategory.BLOCKS, 0.8f, 1.4f);
            }
        }
        return c.health;
    }

    @Override
    public double repair(String id, double amount, @Nullable Player by) {
        Camera c = get(id);
        return c == null ? -1 : repair(c, amount, by);
    }

    public double repair(Camera c, double amount, @Nullable Player by) {
        if (amount <= 0 || c.health >= c.type.maxHealth()) return c.health;
        CameraRepairEvent event = new CameraRepairEvent(c, by, amount);
        if (!event.callEvent() || event.getAmount() <= 0) return -1;
        change(c, () -> c.health = Math.min(c.type.maxHealth(), c.health + event.getAmount()),
                CameraEnableEvent.Reason.REPAIRED);
        World w = c.bukkitWorld();
        if (w != null) {
            w.playSound(new Location(w, c.x, c.y, c.z), "minecraft:block.anvil.use", SoundCategory.BLOCKS, 0.5f, 1.6f);
        }
        return c.health;
    }

    /** Damages every camera within the configured radius of an explosion, falling off linearly with distance. */
    public void explosion(Location at, @Nullable Entity source) {
        World w = at.getWorld();
        double r = cfg.explosionRadius;
        if (w == null || r <= 0 || cfg.explosionDamage <= 0) return;
        for (Camera c : List.copyOf(byId.values())) {
            if (!c.world.equals(w.getName())) continue;
            double d = Math.sqrt(square(c.x - at.getX()) + square(c.y - at.getY()) + square(c.z - at.getZ()));
            if (d < r) damage(c, cfg.explosionDamage * (1 - d / r), source, CameraDamageEvent.Cause.EXPLOSION);
        }
    }

    private static double square(double v) {
        return v * v;
    }

    // ------------------------------------------------------------------ PTZ

    @Override
    public boolean aim(String id, float pan, float tilt) {
        Camera c = get(id);
        if (c == null || !c.type.ptz()) return false;
        aimRaw(c, pan, tilt);
        return true;
    }

    /** Sets the target regardless of the PTZ flag (admin re-aiming of fixed cameras). */
    public void aimRaw(Camera c, float pan, float tilt) {
        c.targetPan = Math.clamp(pan, c.panMin, c.panMax);
        c.targetTilt = Math.clamp(tilt, c.tiltMin, c.tiltMax);
        if (c.targetPan != c.pan || c.targetTilt != c.tilt) motor.wake(c);
    }

    // ------------------------------------------------------------------ access

    @Override
    public ClearanceProvider clearanceProvider() {
        return clearance;
    }

    @Override
    public void setClearanceProvider(ClearanceProvider provider) {
        this.clearance = Objects.requireNonNull(provider, "provider");
    }

    @Override
    public boolean canAccess(Player player, CameraView camera) {
        return player.hasPermission("cctv.view") && clearance.hasClearance(player, camera.clearance());
    }

    // ------------------------------------------------------------------ viewing

    /** The camera's model entities (bracket and lens) while its chunk is loaded. */
    public List<Entity> modelEntities(Camera c) {
        return parts.models(c);
    }

    public void setViewBridge(ViewBridge views) {
        this.views = views;
    }

    @Override
    public boolean startViewing(Player player, String cameraId) {
        Camera c = get(cameraId);
        return c != null && views != null && views.start(player, c);
    }

    @Override
    public boolean stopViewing(Player player) {
        return views != null && views.stop(player);
    }

    @Override
    public Optional<CameraView> viewing(Player player) {
        return views == null ? Optional.empty() : views.viewing(player);
    }

    @Override
    public boolean alerting(String cameraId) {
        Camera c = get(cameraId);
        return c != null && c.alerting();
    }

    /** Next/previous camera in the same group (network), wrapping around, by id order. */
    public @Nullable Camera neighbour(Camera from, int direction, boolean viewableOnly) {
        List<Camera> net = new ArrayList<>();
        for (Camera c : byId.values()) if (c.group.equals(from.group)) net.add(c);
        net.sort((a, b) -> naturalCompare(a.id, b.id));
        int i = net.indexOf(from);
        for (int step = 1; step < net.size(); step++) {
            Camera c = net.get(Math.floorMod(i + direction * step, net.size()));
            if (!viewableOnly || c.state().viewable()) return c;
        }
        return null;
    }

    /** cam-2 before cam-10. */
    public static int naturalCompare(String a, String b) {
        int ia = a.length(), ib = b.length();
        while (ia > 0 && Character.isDigit(a.charAt(ia - 1))) ia--;
        while (ib > 0 && Character.isDigit(b.charAt(ib - 1))) ib--;
        String pa = a.substring(0, ia), pb = b.substring(0, ib);
        if (pa.equals(pb) && ia < a.length() && ib < b.length() && a.length() - ia < 10 && b.length() - ib < 10) {
            return Integer.compare(Integer.parseInt(a.substring(ia)), Integer.parseInt(b.substring(ib)));
        }
        return a.compareTo(b);
    }

    // ------------------------------------------------------------------ internals

    /**
     * Runs a mutation and fires CameraDisableEvent/CameraEnableEvent if it crossed the viewable boundary, swapping
     * the head model when the DESTROYED state changes.
     */
    private void change(Camera c, Runnable mutation, CameraEnableEvent.Reason enableReason) {
        CameraState before = c.state();
        mutation.run();
        CameraState after = c.state();
        dirty = true;
        if ((before == CameraState.DESTROYED) != (after == CameraState.DESTROYED)) parts.refreshModel(c);
        if (before.viewable() && !after.viewable()) {
            new CameraDisableEvent(c, switch (after) {
                case DESTROYED -> CameraDisableEvent.Reason.DESTROYED;
                case UNPOWERED -> CameraDisableEvent.Reason.POWER;
                default -> CameraDisableEvent.Reason.ADMIN;
            }).callEvent();
        } else if (!before.viewable() && after.viewable()) {
            new CameraEnableEvent(c, enableReason).callEvent();
        }
    }

    /** Resolves a stored type key; a type removed from config falls back to the first configured type. */
    private CameraType resolveType(String key) {
        CameraType t = cfg.types.get(key);
        if (t != null) return t;
        CameraType fallback = cfg.types.values().iterator().next();
        log.warning("camera type '" + key + "' is not in config.yml; showing those cameras as '" + fallback.key()
                + "' (the stored type is kept)");
        return fallback;
    }

}

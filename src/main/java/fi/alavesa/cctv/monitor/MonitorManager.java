package fi.alavesa.cctv.monitor;

import fi.alavesa.cctv.api.CameraState;
import fi.alavesa.cctv.api.event.MonitorInteractEvent;
import fi.alavesa.cctv.camera.Camera;
import fi.alavesa.cctv.camera.CameraManager;
import fi.alavesa.cctv.camera.Orientation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Rotation;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.GameMode;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Monitors: placement, entities (one invisible fixed glow item frame with a map per tile, plus bezel displays),
 * and the live feed pipeline.
 *
 * <p>Per tick (main thread): every 10 ticks work out who is watching each monitor (in range, in front, line of
 * sight). Only watched monitors do anything. A frame that is due gathers chunk snapshots within the per-tick ms
 * budget, then is ray-cast on the render pool. When the image comes back, the main thread cuts it into 128x128
 * tiles, stamps the CRT sentinel and pushes the maps to the watchers. States that need no camera image (no signal,
 * offline, booting, off) are a static frame sent once; the CRT shader animates them on the client.
 */
public final class MonitorManager {

    private final Plugin plugin;
    private final Logger log;
    private final CameraManager cameras;
    private final MonitorStore store;
    private final SnapshotCache cache;
    private final BlockColors colors;
    private final FeedRenderer renderer;
    private final NamespacedKey monitorKey;
    private final NamespacedKey placerKey;
    private final NamespacedKey partKey;
    private final Map<String, Monitor> byId = new LinkedHashMap<>();
    /** frame block -> monitor (blocks in front of the back blocks), "world:x:y:z" */
    private final Map<String, Monitor> byFrameBlock = new HashMap<>();
    private final Map<String, Monitor> byBackBlock = new HashMap<>();
    private final AtomicInteger queued = new AtomicInteger();
    private ExecutorService pool;
    private MonitorConfig cfg;
    private @Nullable BukkitTask task;
    private int nextId = 1;
    private boolean dirty;
    private long tick;
    private int governor;
    private double budgetUsedMs;
    private PlacementHook viewStarter = (p, cameraId) -> false;
    private java.util.function.Supplier<List<Map.Entry<Player, Location>>> npcBodies = List::of;
    private final Skins skins;
    /** walking animation per figure: last x, z, tick sampled, limb position, limb amount */
    private final Map<UUID, double[]> walk = new HashMap<>();
    /** tick of each player's last arm swing */
    private final Map<UUID, Long> swings = new HashMap<>();

    /** How a monitor click opens a feed (the view manager), kept as a hook to avoid a package cycle. */
    @FunctionalInterface
    public interface PlacementHook {
        boolean view(Player player, String cameraId);
    }

    public MonitorManager(Plugin plugin, CameraManager cameras, MonitorConfig cfg) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.skins = new Skins(log);
        this.cameras = cameras;
        this.cfg = cfg;
        this.store = new MonitorStore(plugin.getDataFolder().toPath().resolve("monitors.yml"), log);
        this.cache = new SnapshotCache(cfg.cacheTtlTicks);
        this.colors = new BlockColors(log);
        this.renderer = new FeedRenderer(new BlockLook(colors, log));
        this.monitorKey = new NamespacedKey(plugin, "monitor");
        this.placerKey = new NamespacedKey(plugin, "monitor_placer");
        this.partKey = new NamespacedKey(plugin, "part");
        this.pool = newPool(cfg.threads);
    }

    /** FancyNpcs body doubles (viewer, where the NPC stands); set by the plugin, empty without FancyNpcs. */
    public void setNpcBodies(java.util.function.Supplier<List<Map.Entry<Player, Location>>> bodies) {
        this.npcBodies = bodies;
    }

    public void setViewStarter(PlacementHook hook) {
        this.viewStarter = hook;
    }

    private static ExecutorService newPool(int threads) {
        AtomicInteger n = new AtomicInteger();
        return Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "cctv-feed-" + n.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
    }

    // ------------------------------------------------------------------ lifecycle

    public void load() {
        MonitorStore.Loaded l = store.load(cfg.types);
        for (Monitor m : l.monitors()) register(m);
        nextId = l.nextId();
        for (Monitor m : byId.values()) spawn(m);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
        log.info("Loaded " + byId.size() + " monitor(s)");
    }

    public void shutdown() {
        if (task != null) task.cancel();
        pool.shutdownNow();
        for (Monitor m : byId.values()) despawn(m);
        store.saveNowAndClose(store.serialise(byId.values(), nextId));
    }

    public void reload(MonitorConfig cfg) {
        boolean threads = cfg.threads != this.cfg.threads;
        this.cfg = cfg;
        cache.ttl(cfg.cacheTtlTicks);
        if (threads) {
            pool.shutdown();
            pool = newPool(cfg.threads);
        }
        for (Monitor m : byId.values()) {
            despawn(m);
            m.type = cfg.types.getOrDefault(m.typeKey, m.type);
            m.shownKey = "";
            spawn(m);
        }
    }

    public MonitorConfig config() {
        return cfg;
    }

    public Collection<Monitor> monitors() {
        return Collections.unmodifiableCollection(byId.values());
    }

    public @Nullable Monitor get(String id) {
        return byId.get(id.toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ placement

    public enum PlaceResult { OK, BAD_FACE, NO_ROOM, NEEDS_WALL, OCCUPIED, UNKNOWN_TYPE }

    public record Placed(PlaceResult result, @Nullable Monitor monitor) {
    }

    /** Places a monitor of a type against a clicked block face. */
    public Placed place(String typeKey, Block clicked, BlockFace face, Player placer) {
        MonitorConfig.Type type = cfg.types.get(typeKey.toLowerCase(Locale.ROOT));
        if (type == null) return new Placed(PlaceResult.UNKNOWN_TYPE, null);
        BlockFace facing;
        Block back;
        if (type.mount() == MonitorConfig.Mount.DESK) {
            if (face != BlockFace.UP) return new Placed(PlaceResult.BAD_FACE, null);
            back = clicked.getRelative(BlockFace.UP);
            facing = placer.getFacing().getOppositeFace();
            if (!back.getType().isAir() || back.getRelative(facing).isSolid()) {
                return new Placed(PlaceResult.NO_ROOM, null);
            }
        } else {
            if (!face.isCartesian() || face == BlockFace.UP || face == BlockFace.DOWN) {
                return new Placed(PlaceResult.BAD_FACE, null);
            }
            back = clicked;
            facing = face;
        }
        Monitor m = new Monitor("mon-" + nextId, type.key(), type, clicked.getWorld().getName(), back.getX(),
                back.getY(), back.getZ(), facing);
        World w = clicked.getWorld();
        for (int i = 0; i < type.width(); i++) {
            for (int j = 0; j < type.height(); j++) {
                int[] b = m.backBlock(i, j);
                Block bb = w.getBlockAt(b[0], b[1], b[2]);
                Block fb = bb.getRelative(facing);
                if (type.mount() == MonitorConfig.Mount.WALL && !bb.isSolid()) {
                    return new Placed(PlaceResult.NEEDS_WALL, null);
                }
                if (fb.isSolid()) return new Placed(PlaceResult.NO_ROOM, null);
                if (byFrameBlock.containsKey(blockKey(fb)) || byBackBlock.containsKey(blockKey(bb))) {
                    return new Placed(PlaceResult.OCCUPIED, null);
                }
            }
        }
        nextId++;
        if (type.mount() == MonitorConfig.Mount.DESK) back.setType(Material.BARRIER, false);
        register(m);
        m.bootUntil = tick + cfg.bootTicks;
        spawn(m);
        dirty = true;
        return new Placed(PlaceResult.OK, m);
    }

    public boolean remove(String id) {
        Monitor m = byId.remove(id.toLowerCase(Locale.ROOT));
        if (m == null) return false;
        despawn(m);
        unindex(m);
        World w = m.bukkitWorld();
        if (w != null && m.type.mount() == MonitorConfig.Mount.DESK) {
            Block b = w.getBlockAt(m.bx, m.by, m.bz);
            if (b.getType() == Material.BARRIER) b.setType(Material.AIR, false);
        }
        dirty = true;
        return true;
    }

    private void register(Monitor m) {
        byId.put(m.id, m);
        for (int i = 0; i < m.type.width(); i++) {
            for (int j = 0; j < m.type.height(); j++) {
                int[] b = m.backBlock(i, j);
                byBackBlock.put(key(m.world, b[0], b[1], b[2]), m);
                byFrameBlock.put(key(m.world, b[0] + m.facing.getModX(), b[1], b[2] + m.facing.getModZ()), m);
            }
        }
    }

    private void unindex(Monitor m) {
        byBackBlock.values().removeIf(x -> x == m);
        byFrameBlock.values().removeIf(x -> x == m);
    }

    private static String blockKey(Block b) {
        return key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }

    private static String key(String w, int x, int y, int z) {
        return w + ":" + x + ":" + y + ":" + z;
    }

    public @Nullable Monitor atBackBlock(Block b) {
        return byBackBlock.get(blockKey(b));
    }

    public ItemStack placer(MonitorConfig.Type type, int amount) {
        ItemStack item = new ItemStack(Material.PAPER, Math.clamp(amount, 1, 64));
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(type.placerModel());
        meta.itemName(type.name());
        meta.lore(List.of(Component.text(type.width() + "x" + type.height() + " "
                        + (type.mount() == MonitorConfig.Mount.DESK ? "desk (place on top of a block)"
                        : "wall (place on a wall)"), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(placerKey, PersistentDataType.STRING, type.key());
        item.setItemMeta(meta);
        return item;
    }

    public @Nullable String placerType(@Nullable ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(placerKey, PersistentDataType.STRING);
    }

    // ------------------------------------------------------------------ settings

    public void bind(Monitor m, List<String> cameraIds, int cycleSeconds) {
        m.cameras.clear();
        for (String c : cameraIds) m.cameras.add(c.toLowerCase(Locale.ROOT));
        m.cycleSeconds = cycleSeconds;
        m.cycleIndex = 0;
        m.cycleSince = tick;
        m.shownKey = "";
        m.staticUntil = tick + cfg.switchStaticTicks;
        dirty = true;
    }

    public void setMode(Monitor m, Monitor.Mode mode) {
        m.mode = mode;
        m.shownKey = "";
        m.nextFrameAt = 0;
        dirty = true;
    }

    public void setPowered(Monitor m, boolean on) {
        if (m.powered == on) return;
        m.powered = on;
        m.shownKey = "";
        if (on) m.bootUntil = tick + cfg.bootTicks;
        World w = m.bukkitWorld();
        if (w != null) {
            w.playSound(center(m), on ? "minecraft:block.beacon.activate" : "cctv:power.off", SoundCategory.BLOCKS,
                    0.6f, on ? 1.8f : 1f);
        }
        dirty = true;
    }

    // ------------------------------------------------------------------ entities

    private void spawn(Monitor m) {
        if (m.frames != null) return;
        World w = m.bukkitWorld();
        if (w == null) return;
        for (int i = 0; i < m.type.width(); i++) {
            int[] b = m.backBlock(i, 0);
            if (!w.isChunkLoaded(b[0] >> 4, b[2] >> 4)) return;
        }
        setupMaps(m, w);
        int n = m.tileCount();
        m.frames = new UUID[n];
        m.bezels = new UUID[n];
        Quaternionf rot = Orientation.head(Orientation.faceYaw(m.facing), 0);
        for (int i = 0; i < m.type.width(); i++) {
            for (int j = 0; j < m.type.height(); j++) {
                int t = j * m.type.width() + i;
                int[] b = m.backBlock(i, j);
                Location front = new Location(w, b[0] + m.facing.getModX(), b[1], b[2] + m.facing.getModZ());
                ItemStack map = new ItemStack(Material.FILLED_MAP);
                MapMeta meta = (MapMeta) map.getItemMeta();
                meta.setMapView(m.views[t]);
                map.setItemMeta(meta);
                try {
                    m.frames[t] = w.spawn(front, GlowItemFrame.class, f -> {
                        f.setFacingDirection(m.facing, true);
                        f.setItem(map, false);
                        f.setRotation(Rotation.NONE);
                        f.setVisible(false);
                        f.setFixed(true);
                        f.setInvulnerable(true);
                        f.setPersistent(false);
                        tag(f, m, "screen");
                    }).getUniqueId();
                } catch (IllegalArgumentException e) {
                    log.log(Level.WARNING, "monitor " + m.id + ": could not hang a frame at " + front.toVector(), e);
                }
                // bezel: one per tile on walls, one body for a desk monitor
                int ti = i, tj = j;
                Location c = new Location(w, b[0] + 0.5, b[1] + 0.5, b[2] + 0.5);
                m.bezels[t] = w.spawn(c, ItemDisplay.class, d -> {
                    d.setPersistent(false);
                    d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                    d.setBillboard(Display.Billboard.FIXED);
                    ItemStack it = new ItemStack(Material.PAPER);
                    ItemMeta im = it.getItemMeta();
                    im.setItemModel(m.type.bezelModel(ti, tj));
                    it.setItemMeta(im);
                    d.setItemStack(it);
                    d.setTransformation(new Transformation(new Vector3f(), rot, new Vector3f(1, 1, 1),
                            new Quaternionf()));
                    tag(d, m, "bezel");
                }).getUniqueId();
            }
        }
        standby(m);
    }

    /**
     * What a monitor shows until someone watches it (e.g. after a restart, or to players beyond watch-radius):
     * animated static over a dim "STANDBY" (the shader draws the static), or a dark screen when switched off.
     * Delivered by the vanilla item-frame map updates, so it reaches everyone who can see the frame.
     */
    private void standby(Monitor m) {
        int w = m.type.width() * Screen.SIZE, h = m.type.height() * Screen.SIZE;
        byte[] img = new byte[w * h];
        if (m.powered) Screen.textFrame(img, w, h, "STANDBY", m.currentCamera() == null ? "" : m.currentCamera());
        else java.util.Arrays.fill(img, Screen.BACKGROUND);
        publish(m, img, w, h, 1, m.powered ? Screen.State.STATIC : Screen.State.OFF, m.type.style());
        m.shownKey = "";
    }

    private void despawn(Monitor m) {
        if (m.frames != null) for (UUID u : m.frames) remove(u);
        if (m.bezels != null) for (UUID u : m.bezels) remove(u);
        m.frames = null;
        m.bezels = null;
    }

    private static void remove(@Nullable UUID u) {
        Entity e = u == null ? null : Bukkit.getEntity(u);
        if (e != null) e.remove();
    }

    private void tag(Entity e, Monitor m, String part) {
        e.getPersistentDataContainer().set(monitorKey, PersistentDataType.STRING, m.id);
        e.getPersistentDataContainer().set(partKey, PersistentDataType.STRING, part);
    }

    /** Map ids are created once per tile and kept forever (no map_N.dat growth on restarts). */
    @SuppressWarnings("deprecation") // Bukkit.getMap(int): ids are the only stable handle we can persist
    private void setupMaps(Monitor m, World w) {
        int n = m.tileCount();
        if (m.views == null) {
            m.views = new MapView[n];
            m.renderers = new TileRenderer[n];
        }
        for (int t = 0; t < n; t++) {
            if (m.views[t] != null) continue;
            MapView v = m.mapIds[t] >= 0 ? Bukkit.getMap(m.mapIds[t]) : null;
            if (v == null) {
                v = Bukkit.createMap(w);
                m.mapIds[t] = v.getId();
                dirty = true;
            }
            for (MapRenderer r : List.copyOf(v.getRenderers())) v.removeRenderer(r);
            v.setTrackingPosition(false);
            v.setUnlimitedTracking(false);
            v.setLocked(true);
            TileRenderer tr = new TileRenderer(m.tiles[t]);
            v.addRenderer(tr);
            m.views[t] = v;
            m.renderers[t] = tr;
        }
    }

    /** The monitor an entity (frame or bezel) belongs to. */
    public @Nullable Monitor of(Entity e) {
        String id = e.getPersistentDataContainer().get(monitorKey, PersistentDataType.STRING);
        return id == null ? null : byId.get(id);
    }

    public boolean isTagged(Entity e) {
        return e.getPersistentDataContainer().has(monitorKey);
    }

    public boolean isLive(Entity e) {
        Monitor m = of(e);
        if (m == null) return false;
        UUID u = e.getUniqueId();
        if (m.frames != null) for (UUID x : m.frames) if (u.equals(x)) return true;
        if (m.bezels != null) for (UUID x : m.bezels) if (u.equals(x)) return true;
        return false;
    }

    public void onChunkLoad(Chunk c) {
        for (Monitor m : byId.values()) if (touches(m, c)) spawn(m);
    }

    public void onChunkUnload(Chunk c) {
        for (Monitor m : byId.values()) if (touches(m, c)) despawn(m);
    }

    private static boolean touches(Monitor m, Chunk c) {
        if (!c.getWorld().getName().equals(m.world)) return false;
        for (int i = 0; i < m.type.width(); i++) {
            int[] b = m.backBlock(i, 0);
            if (b[0] >> 4 == c.getX() && b[2] >> 4 == c.getZ()) return true;
        }
        return false;
    }

    public void invalidate(Block b) {
        cache.invalidate(b.getWorld(), b.getX() >> 4, b.getZ() >> 4);
    }

    // ------------------------------------------------------------------ interaction

    /** A player clicked a monitor screen: open the camera it shows (the clicked quadrant in quad mode). */
    public void interact(Player p, Monitor m) {
        if (!m.powered || m.cameras.isEmpty()) return;
        String cam = m.mode == Monitor.Mode.QUAD ? quadrantCamera(p, m) : m.currentCamera();
        if (cam == null) return;
        if (!new MonitorInteractEvent(p, m.id, cam).callEvent()) return;
        viewStarter.view(p, cam);
    }

    private @Nullable String quadrantCamera(Player p, Monitor m) {
        Vector n = new Vector(m.facing.getModX(), 0, m.facing.getModZ());
        Vector right = new Vector(m.rightX(), 0, m.rightZ());
        // bottom-left corner of the screen surface
        Vector corner = new Vector(m.bx + 0.5, m.by, m.bz + 0.5).add(n.clone().multiply(0.5))
                .subtract(right.clone().multiply(0.5));
        Vector eye = p.getEyeLocation().toVector(), dir = p.getEyeLocation().getDirection();
        double denom = dir.dot(n);
        if (Math.abs(denom) < 1e-6) return m.currentCamera();
        double t = corner.clone().subtract(eye).dot(n) / denom;
        Vector hit = eye.add(dir.multiply(t)).subtract(corner);
        double u = hit.dot(right) / m.type.width(), v = hit.getY() / m.type.height();
        int q = (v > 0.5 ? 0 : 2) + (u < 0.5 ? 0 : 1);
        return q < m.cameras.size() ? m.cameras.get(q) : m.currentCamera();
    }

    // ------------------------------------------------------------------ the scheduler

    private void tick() {
        tick++;
        budgetUsedMs = 0;
        if (tick % 40 == 0) governor();
        if (tick % 100 == 0) {
            cache.sweep(tick);
            if (dirty) {
                dirty = false;
                store.saveAsync(store.serialise(byId.values(), nextId));
            }
        }
        boolean watchersDue = tick % 10 == 0;
        for (Monitor m : byId.values()) {
            if (m.frames == null) continue;
            if (watchersDue) updateWatchers(m);
            if (m.watchers.isEmpty()) continue;
            hum(m);
            step(m);
        }
    }

    private void governor() {
        double mspt = Bukkit.getServer().getAverageTickTime();
        if (mspt > cfg.governorHighMspt && governor < 3) governor++;
        else if (mspt < cfg.governorLowMspt && governor > 0) governor--;
    }

    private void updateWatchers(Monitor m) {
        m.watchers.clear();
        World w = m.bukkitWorld();
        if (w == null) return;
        Location c = center(m);
        double r2 = cfg.watchRadius * cfg.watchRadius;
        Vector n = new Vector(m.facing.getModX(), 0, m.facing.getModZ());
        for (Player p : w.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue; // includes people viewing a camera full-screen
            Location eye = p.getEyeLocation();
            if (eye.distanceSquared(c) > r2) continue;
            Vector to = eye.toVector().subtract(c.toVector());
            if (to.dot(n) <= 0) continue; // behind the screen
            if (cfg.watchNeedsLineOfSight) {
                Vector dir = c.toVector().subtract(eye.toVector());
                double len = dir.length();
                RayTraceResult hit = w.rayTraceBlocks(eye, dir.normalize(), Math.max(0, len - 0.6),
                        FluidCollisionMode.NEVER, true);
                if (hit != null && hit.getHitBlock() != null && atBackBlock(hit.getHitBlock()) != m) continue;
            }
            m.watchers.add(p.getUniqueId());
        }
    }

    private void hum(Monitor m) {
        if (!m.powered || cfg.humIntervalTicks <= 0 || cfg.humSound.isBlank() || tick < m.humAt) return;
        m.humAt = tick + cfg.humIntervalTicks;
        World w = m.bukkitWorld();
        if (w != null) w.playSound(center(m), cfg.humSound, SoundCategory.BLOCKS, 0.25f, 1f);
    }

    Location center(Monitor m) {
        World w = m.bukkitWorld();
        double cx = m.bx + 0.5 + m.facing.getModX() * 0.5 + m.rightX() * (m.type.width() - 1) / 2.0;
        double cz = m.bz + 0.5 + m.facing.getModZ() * 0.5 + m.rightZ() * (m.type.width() - 1) / 2.0;
        return new Location(w, cx, m.by + m.type.height() / 2.0, cz);
    }

    /** Decides what the monitor shows this tick and produces it. */
    private void step(Monitor m) {
        int w = m.type.width() * Screen.SIZE, h = m.type.height() * Screen.SIZE;
        if (!m.powered) {
            staticState(m, Screen.State.OFF, "", "", w, h);
            return;
        }
        if (tick < m.bootUntil) {
            staticState(m, Screen.State.BOOT, "CCTV", "BOOTING", w, h);
            return;
        }
        // cycling
        if (m.mode != Monitor.Mode.QUAD && m.cameras.size() > 1) {
            int secs = m.cycleSeconds > 0 ? m.cycleSeconds : cfg.defaultCycleSeconds;
            if (tick - m.cycleSince >= secs * 20L) {
                m.cycleIndex++;
                m.cycleSince = tick;
                m.staticUntil = tick + cfg.switchStaticTicks;
                m.nextFrameAt = 0;
            }
        }
        List<Camera> cams = shownCameras(m);
        if (cams.isEmpty()) {
            staticState(m, Screen.State.NO_SIGNAL, "NO SIGNAL", m.cameras.isEmpty() ? "NO CAMERA" : "", w, h);
            return;
        }
        if (cams.size() == 1) {
            Camera c = cams.getFirst();
            CameraState st = c.state();
            if (!st.viewable()) {
                staticState(m, Screen.State.OFFLINE,
                        st == CameraState.UNPOWERED ? "POWER FAILURE" : "CAMERA OFFLINE", c.id(), w, h);
                return;
            }
            if (m.mode == Monitor.Mode.SHADER) {
                standIn(m, c, w, h);
                return;
            }
        }
        if (m.rendering || tick < m.nextFrameAt) {
            if (tick < m.staticUntil && m.shown == Screen.State.LIVE) restamp(m, Screen.State.STATIC);
            return;
        }
        submit(m, cams, w, h);
    }

    /** Cameras feeding the monitor now: 1, or up to 4 in quad mode (missing / offline ones are skipped). */
    private List<Camera> shownCameras(Monitor m) {
        List<Camera> out = new ArrayList<>(4);
        if (m.mode == Monitor.Mode.QUAD) {
            for (int i = 0; i < Math.min(4, m.cameras.size()); i++) {
                Camera c = cameras.get(m.cameras.get(i));
                if (c != null) out.add(c);
            }
            return out;
        }
        String id = m.currentCamera();
        Camera c = id == null ? null : cameras.get(id);
        if (c != null) out.add(c);
        return out;
    }

    private void staticState(Monitor m, Screen.State state, String title, String detail, int w, int h) {
        String key = state + "|" + title + "|" + detail;
        if (key.equals(m.shownKey)) return;
        byte[] img = new byte[w * h];
        if (state == Screen.State.OFF) java.util.Arrays.fill(img, Screen.BACKGROUND);
        else Screen.textFrame(img, w, h, title, detail);
        publish(m, img, w, h, 1, state, m.type.style());
        m.shownKey = key;
    }

    /** Shader-only mode: a stylised stand-in (camera name over a grid) sent once; the CRT shader animates it. */
    private void standIn(Monitor m, Camera c, int w, int h) {
        String key = "STANDIN|" + c.id();
        if (key.equals(m.shownKey)) return;
        byte[] img = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean grid = x % 16 == 0 || y % 16 == 0;
                img[y * w + x] = grid ? MapColors.pixel(MapColors.GRAY, 3) : MapColors.pixel(MapColors.BLACK, 2);
            }
        }
        Screen.drawCentered(img, w, h, c.id().toUpperCase(), h / 2 - 8, 2, Screen.TEXT);
        Screen.drawCentered(img, w, h, c.name().toUpperCase(), h / 2 + 10, 1, Screen.DIM_TEXT);
        publish(m, img, w, h, 1, Screen.State.LIVE, styleFor(m, List.of(c)));
        m.shownKey = key;
    }

    private void submit(Monitor m, List<Camera> cams, int w, int h) {
        int scale = Math.min(4, cfg.renderScale * (governor >= 2 ? 2 : 1));
        int rw = w / scale, rh = h / scale;
        boolean quad = m.mode == Monitor.Mode.QUAD;
        World world = cams.getFirst().bukkitWorld();
        if (world == null) return;
        FeedRenderer.Pose[] poses = new FeedRenderer.Pose[quad ? 4 : 1];
        FeedRenderer.Ent[][] ents = new FeedRenderer.Ent[poses.length][];
        Map<Long, ChunkSnapshot> chunks = new HashMap<>();
        for (int i = 0; i < poses.length; i++) {
            Camera c = cams.get(Math.min(i, cams.size() - 1));
            if (quad && i >= cams.size()) c = cams.getLast();
            Location eye = c.location();
            if (eye.getWorld() != world) continue;
            poses[i] = new FeedRenderer.Pose(eye.getX(), eye.getY(), eye.getZ(), c.yaw(), c.pitch(), c.fov(),
                    look(c));
            if (!gatherChunks(world, poses[i], chunks)) return; // budget spent: try again next tick
            ents[i] = entities(world, eye, poses[i]);
        }
        for (int i = 0; i < poses.length; i++) {
            if (poses[i] == null) {
                poses[i] = poses[0];
                ents[i] = ents[0];
            }
        }
        FeedRenderer.Job job = new FeedRenderer.Job(rw, rh, poses, ents, chunks, world.getMinHeight(),
                world.getMaxHeight(), daylight(world), cfg.maxDistance);
        int style = styleFor(m, cams);
        m.rendering = true;
        m.nextFrameAt = tick + interval(m);
        queued.incrementAndGet();
        pool.execute(() -> {
            long t0 = System.nanoTime();
            byte[] img;
            try {
                img = renderer.render(job);
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "feed render failed for " + m.id, e);
                img = null;
            }
            double ms = (System.nanoTime() - t0) / 1e6;
            byte[] result = img;
            queued.decrementAndGet();
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                m.rendering = false;
                if (result == null || !byId.containsKey(m.id) || m.frames == null) return;
                m.renderMsAvg = m.frameCount == 0 ? ms : m.renderMsAvg * 0.8 + ms * 0.2;
                m.frameCount++;
                Screen.State st = tick < m.staticUntil ? Screen.State.STATIC : Screen.State.LIVE;
                publish(m, result, rw, rh, scale, st, style);
                m.shownKey = "LIVE";
            });
        });
    }

    private long interval(Monitor m) {
        double secs = m.mode == Monitor.Mode.SNAPSHOT || governor >= 3 ? cfg.snapshotIntervalSeconds : 1.0 / cfg.fps;
        return Math.max(1, Math.round(secs * 20 * (governor >= 1 && governor < 3 ? 2 : 1)));
    }

    private static int look(Camera c) {
        return switch (c.type().look()) {
            case "night_vision" -> 1;
            case "thermal" -> 2;
            default -> 0;
        };
    }

    private static int styleFor(Monitor m, List<Camera> cams) {
        if (m.mode == Monitor.Mode.QUAD || cams.size() != 1) return m.type.style();
        return switch (look(cams.getFirst())) {
            case 1 -> 1; // green phosphor for night vision
            case 2 -> 3; // thermal ramp
            default -> m.type.style();
        };
    }

    /** Captures missing snapshots for the chunks a pose can see, within this tick's ms budget. */
    private boolean gatherChunks(World w, FeedRenderer.Pose p, Map<Long, ChunkSnapshot> out) {
        int r = (int) Math.ceil(cfg.maxDistance / 16.0) + 1;
        int ccx = (int) Math.floor(p.x()) >> 4, ccz = (int) Math.floor(p.z()) >> 4;
        double yaw = Math.toRadians(p.yaw());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double cone = Math.cos(Math.toRadians(Math.min(89, p.fov() * 0.9 + 30)));
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double vx = dx * 16 + 8 - (p.x() - (ccx << 4)), vz = dz * 16 + 8 - (p.z() - (ccz << 4));
                double len = Math.sqrt(vx * vx + vz * vz);
                if (len > cfg.maxDistance + 24) continue;
                // keep chunks near the camera and those inside the (widened) horizontal view cone
                if (len > 24 && (vx * fx + vz * fz) / len < cone && Math.abs(p.pitch()) < 60) continue;
                int cx = ccx + dx, cz = ccz + dz;
                long key = Chunk.getChunkKey(cx, cz);
                if (out.containsKey(key)) continue;
                ChunkSnapshot s = cache.fresh(w, cx, cz, tick);
                if (s == null) {
                    if (budgetUsedMs >= cfg.msBudgetPerTick) return false;
                    long t0 = System.nanoTime();
                    s = cache.capture(w, cx, cz, tick);
                    budgetUsedMs += (System.nanoTime() - t0) / 1e6;
                }
                if (s != null) out.put(key, s);
            }
        }
        return true;
    }

    private FeedRenderer.Ent[] entities(World w, Location eye, FeedRenderer.Pose p) {
        double d = cfg.maxDistance;
        List<FeedRenderer.Ent> out = new ArrayList<>();
        for (Entity e : w.getNearbyEntities(eye, d, d, d)) {
            if (e instanceof Hanging || e instanceof Display || e instanceof Interaction) continue;
            if (e.getPersistentDataContainer().has(partKey)) continue; // camera anchors, monitor parts
            if (e instanceof Player pl && pl.getGameMode() == GameMode.SPECTATOR) continue;
            if (e instanceof LivingEntity le && le.isInvisible()) continue;
            if (e instanceof Player pl) {
                out.add(figure(pl.getLocation(), pl.getBodyYaw(), skins.of(pl.getPlayerProfile().getProperties()),
                        pl.getEquipment(), anim(pl)));
                continue;
            }
            if (e instanceof Mannequin mq) {
                out.add(figure(mq.getLocation(), mq.getBodyYaw(), skins.of(mq.getProfile()),
                        mq.getEquipment(), anim(mq)));
                continue;
            }
            int base; // silhouette colour by category
            if (e instanceof Monster) base = 0xB33A2E;
            else if (e instanceof Animals) base = 0x8FA35A;
            else if (e instanceof LivingEntity) base = 0x7FA0B0;
            else if (e instanceof Item) base = 0xE0C040;
            else base = 0x9A9A9A;
            BoundingBox b = e.getBoundingBox();
            out.add(new FeedRenderer.Ent(b.getMinX(), b.getMinY(), b.getMinZ(), b.getMaxX(), b.getMaxY(), b.getMaxZ(),
                    base, null));
        }
        // FancyNpcs body doubles are client-side NPCs (no server entity): draw the viewer where they stand
        for (Map.Entry<Player, Location> b : npcBodies.get()) {
            Location at = b.getValue();
            if (at.getWorld() != w || at.distanceSquared(eye) > d * d) continue;
            Player pl = b.getKey();
            // the NPC stands still, looking where the viewer looked when they sat down
            out.add(figure(at, at.getYaw(), skins.of(pl.getPlayerProfile().getProperties()), pl.getEquipment(),
                    new Figure.Anim(0, at.getPitch(), 0, 0, false, -1, Figure.Lying.NO, false, tick)));
        }
        return out.toArray(new FeedRenderer.Ent[0]);
    }

    /** A player's arm swing (main thread); its figures swing the right arm for the next few ticks. */
    void swung(UUID player) {
        swings.put(player, tick);
    }

    /** How a living figure is posed now: walking from how far it moved since the last sample, head, sneak, swing. */
    private Figure.Anim anim(LivingEntity e) {
        Location l = e.getLocation();
        double[] w = walk.get(e.getUniqueId());
        if (w == null) {
            if (walk.size() > 1024) walk.clear();
            w = new double[]{l.getX(), l.getZ(), tick, 0, 0};
            walk.put(e.getUniqueId(), w);
        } else if (tick > w[2]) {
            double dist = Math.hypot(l.getX() - w[0], l.getZ() - w[1]);
            double ticks = tick - w[2];
            // the client: limb amount follows 4x the speed in blocks/tick, the limb position advances by it each tick
            double speed = dist / ticks;
            w[4] = Math.min(1, speed * 4);
            w[3] += dist * 4;
            w[0] = l.getX();
            w[1] = l.getZ();
            w[2] = tick;
        }
        float bodyYaw = e.getBodyYaw();
        float headYaw = (float) Math.clamp(wrap(l.getYaw() - bodyYaw), -75, 75);
        org.bukkit.entity.Pose pose = e.getPose();
        Figure.Lying lying = switch (pose) {
            case SWIMMING, FALL_FLYING, SPIN_ATTACK -> Figure.Lying.PRONE;
            case SLEEPING, DYING -> Figure.Lying.ON_BACK;
            default -> Figure.Lying.NO;
        };
        boolean sneaking = pose == org.bukkit.entity.Pose.SNEAKING || (e instanceof Player pl && pl.isSneaking());
        Long sw = swings.get(e.getUniqueId());
        float swing = sw != null && tick - sw < 6 ? (tick - sw) / 6f : -1;
        boolean hurt = e.getNoDamageTicks() > e.getMaximumNoDamageTicks() / 2 && e.getLastDamage() > 0;
        return new Figure.Anim(headYaw, l.getPitch(), (float) w[3], (float) w[4], sneaking, swing, lying, hurt,
                tick + (e.getEntityId() * 37 % 100));
    }

    private static double wrap(double deg) {
        deg %= 360;
        if (deg > 180) deg -= 360;
        if (deg < -180) deg += 360;
        return deg;
    }

    private static FeedRenderer.Ent figure(Location at, float bodyYaw, int[] skin,
                                           org.bukkit.inventory.@org.jspecify.annotations.Nullable EntityEquipment eq,
                                           Figure.Anim anim) {
        int[] armour = new int[4];
        if (eq != null) {
            armour[0] = armourRgb(eq.getHelmet());
            armour[1] = armourRgb(eq.getChestplate());
            armour[2] = armourRgb(eq.getLeggings());
            armour[3] = armourRgb(eq.getBoots());
        }
        Figure f = new Figure(at.getX(), at.getY(), at.getZ(), bodyYaw, skin, armour, anim);
        return new FeedRenderer.Ent(f.minX(), f.minY(), f.minZ(), f.maxX(), f.maxY(), f.maxZ(), 0, f);
    }

    /** Colour of a worn armour piece (leather: its dye), 0 for none or for things that aren't armour. */
    private static int armourRgb(@org.jspecify.annotations.Nullable ItemStack it) {
        if (it == null || it.isEmpty()) return 0;
        String n = it.getType().name();
        if (n.startsWith("LEATHER_") && it.getItemMeta() instanceof org.bukkit.inventory.meta.LeatherArmorMeta lm) {
            return lm.getColor().asRGB();
        }
        if (n.startsWith("CHAINMAIL_")) return 0x8C8E92;
        if (n.startsWith("IRON_")) return 0xC9CBCE;
        if (n.startsWith("GOLDEN_")) return 0xE6C34A;
        if (n.startsWith("DIAMOND_")) return 0x52D6D0;
        if (n.startsWith("NETHERITE_")) return 0x4B4547;
        if (n.startsWith("COPPER_")) return 0xB8694A;
        if (n.equals("TURTLE_HELMET")) return 0x4E9B3A;
        return 0;
    }

    private static float daylight(World w) {
        if (w.getEnvironment() != World.Environment.NORMAL) return 0.45f;
        double t = w.getTime() % 24000;
        double v = Math.cos((t - 6000) / 24000.0 * Math.PI * 2) * 0.75 + 0.45;
        return (float) Math.clamp(v, 0.12, 1.0);
    }

    // ------------------------------------------------------------------ output

    /** Cuts an image (iw x ih at the given upscale) into tiles, stamps the sentinel and pushes to watchers. */
    private void publish(Monitor m, byte[] img, int iw, int ih, int scale, Screen.State state, int style) {
        if (m.renderers == null) return;
        int tw = m.type.width(), th = m.type.height();
        for (int i = 0; i < tw; i++) {
            for (int j = 0; j < th; j++) {
                int t = j * tw + i;
                byte[] tile = m.tiles[t];
                int top = (th - 1 - j) * Screen.SIZE;
                for (int y = 0; y < Screen.SIZE; y++) {
                    int sy = Math.min(ih - 1, (top + y) / scale);
                    int row = y * Screen.SIZE;
                    for (int x = 0; x < Screen.SIZE; x++) {
                        int sx = Math.min(iw - 1, (i * Screen.SIZE + x) / scale);
                        tile[row + x] = img[sy * iw + sx];
                    }
                }
                Screen.stamp(tile, state, style, m.type.flat());
                m.renderers[t].changed();
            }
        }
        m.shown = state;
        push(m);
    }

    /** Re-stamps the state pixel only (e.g. STATIC during a cycle switch) without a new image. */
    private void restamp(Monitor m, Screen.State state) {
        if (m.renderers == null || m.shown == state) return;
        for (int t = 0; t < m.tileCount(); t++) {
            m.tiles[t][1] = state == Screen.State.STATIC ? MapColors.pixel(MapColors.SNOW, 1)
                    : MapColors.pixel(MapColors.SNOW, 2);
            m.renderers[t].changed();
        }
        m.shown = state;
        push(m);
    }

    private void push(Monitor m) {
        if (!cfg.pushFrames || m.views == null) return;
        for (UUID u : m.watchers) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            for (MapView v : m.views) {
                if (v != null) {
                    p.sendMap(v);
                    m.bytesSent += Screen.SIZE * Screen.SIZE + 16;
                }
            }
        }
    }

    // ------------------------------------------------------------------ perf

    public List<String> perf() {
        List<String> out = new ArrayList<>();
        out.add(String.format(Locale.ROOT, "mspt %.1f  governor %d  render queue %d  chunk cache %d (%.0f%% hits, "
                        + "capture %.2f ms)", Bukkit.getServer().getAverageTickTime(), governor, queued.get(),
                cache.size(), 100.0 * cache.hits / Math.max(1, cache.hits + cache.misses), cache.captureMs));
        for (Monitor m : byId.values()) {
            out.add(String.format(Locale.ROOT, "%s %s %s  watchers %d  frames %d  render %.1f ms  sent %d KB  %s",
                    m.id, m.typeKey, m.mode, m.watchers.size(), m.frameCount, m.renderMsAvg, m.bytesSent / 1024,
                    m.frames == null ? "(unloaded)" : m.shown == null ? "-" : m.shown));
        }
        return out;
    }

    public int governorLevel() {
        return governor;
    }
}

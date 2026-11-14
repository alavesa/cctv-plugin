package fi.alavesa.cctv.alert;

import fi.alavesa.cctv.api.event.CameraAlertEvent;
import fi.alavesa.cctv.camera.Camera;
import fi.alavesa.cctv.camera.CameraManager;
import fi.alavesa.cctv.config.Messages;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * Restricted zones and camera sightings. Every {@code alerts.interval-ticks} it looks for players inside a zone,
 * and for each one checks whether any working camera of the zone's network sees them (range, field of view, one
 * block ray-trace). A sighting puts the camera in alert (the HUD shows it to that network's viewers), fires the
 * cancellable {@link CameraAlertEvent}, and notifies viewers of the network plus players with
 * {@code cctv.alert.receive}. Nothing here is SCP-specific: exemptions are permissions or a cancelled event.
 */
public final class AlertService {

    /** The latest alert of a network, read by the HUD. */
    public record Active(String cameraId, String zone, String intruder, long until) {
    }

    private final Plugin plugin;
    private final Logger log;
    private final CameraManager cameras;
    private final Supplier<Messages> messages;
    private final File file;
    private final Map<String, Zone> zones = new LinkedHashMap<>();
    private final Map<UUID, Location[]> selections = new HashMap<>();
    /** camera|zone|player -> tick of the last alert, for the cooldown */
    private final Map<String, Long> recent = new HashMap<>();
    private final Map<String, Active> byGroup = new HashMap<>();
    private @Nullable BukkitTask task;

    private boolean enabled;
    private int interval;
    private double range;
    private int cooldownTicks;
    private int alertTicks;
    private String sound;

    public AlertService(Plugin plugin, CameraManager cameras, Supplier<Messages> messages, FileConfiguration cfg) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.cameras = cameras;
        this.messages = messages;
        this.file = new File(plugin.getDataFolder(), "zones.yml");
        config(cfg);
    }

    public void config(FileConfiguration c) {
        enabled = c.getBoolean("alerts.enabled", true);
        interval = Math.max(2, c.getInt("alerts.interval-ticks", 10));
        range = c.getDouble("alerts.range", 32);
        cooldownTicks = Math.max(20, c.getInt("alerts.cooldown-seconds", 15) * 20);
        alertTicks = Math.max(20, c.getInt("alerts.alert-seconds", 10) * 20);
        sound = c.getString("alerts.sound", "cctv:alert");
        if (task != null) task.cancel();
        task = enabled ? Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval) : null;
    }

    public void load() {
        zones.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection zs = y.getConfigurationSection("zones");
        if (zs == null) return;
        for (String name : zs.getKeys(false)) {
            ConfigurationSection s = zs.getConfigurationSection(name);
            if (s == null) continue;
            BoundingBox box = new BoundingBox(s.getDouble("min.x"), s.getDouble("min.y"), s.getDouble("min.z"),
                    s.getDouble("max.x"), s.getDouble("max.y"), s.getDouble("max.z"));
            zones.put(name, new Zone(name, s.getString("world", "world"), box, s.getString("group", "default")));
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Zone z : zones.values()) {
            String p = "zones." + z.name() + ".";
            y.set(p + "world", z.world());
            y.set(p + "group", z.group());
            y.set(p + "min.x", z.box().getMinX());
            y.set(p + "min.y", z.box().getMinY());
            y.set(p + "min.z", z.box().getMinZ());
            y.set(p + "max.x", z.box().getMaxX());
            y.set(p + "max.y", z.box().getMaxY());
            y.set(p + "max.z", z.box().getMaxZ());
        }
        try {
            y.save(file); // tiny file, written only on admin edits
        } catch (IOException e) {
            log.log(Level.SEVERE, "could not save zones.yml", e);
        }
    }

    public void shutdown() {
        if (task != null) task.cancel();
    }

    // ------------------------------------------------------------------ zone editing

    /** Stores corner 1 or 2 of a player's selection (the block they stand in). */
    public void select(Player p, int corner) {
        Location[] sel = selections.computeIfAbsent(p.getUniqueId(), k -> new Location[2]);
        sel[corner - 1] = p.getLocation().toBlockLocation();
    }

    /** @return null on success, or the message key of the problem */
    public @Nullable String create(Player p, String name, String group) {
        Location[] sel = selections.get(p.getUniqueId());
        if (sel == null || sel[0] == null || sel[1] == null) return "zone-need-selection";
        if (sel[0].getWorld() != sel[1].getWorld()) return "zone-need-selection";
        // corners are the blocks the admin stood in: add head room so a floor-level selection still holds players
        BoundingBox box = BoundingBox.of(sel[0].getBlock(), sel[1].getBlock()).expand(0, 0, 0, 0, 2, 0);
        zones.put(name.toLowerCase(Locale.ROOT), new Zone(name.toLowerCase(Locale.ROOT), sel[0].getWorld().getName(),
                box, group.toLowerCase(Locale.ROOT)));
        save();
        return null;
    }

    public boolean remove(String name) {
        boolean r = zones.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (r) save();
        return r;
    }

    public Collection<Zone> zones() {
        return zones.values();
    }

    // ------------------------------------------------------------------ detection

    public @Nullable Active active(String group) {
        Active a = byGroup.get(group);
        return a != null && Bukkit.getCurrentTick() < a.until() ? a : null;
    }

    private void tick() {
        if (zones.isEmpty()) return;
        long now = Bukkit.getCurrentTick();
        if (now % 1200 < interval) recent.values().removeIf(t -> now - t > cooldownTicks);
        for (Zone z : zones.values()) {
            World w = Bukkit.getWorld(z.world());
            if (w == null) continue;
            List<Camera> net = new ArrayList<>();
            for (Camera c : cameras.cameras()) {
                if (c.group().equals(z.group()) && c.worldName().equals(z.world()) && c.state().viewable()) net.add(c);
            }
            if (net.isEmpty()) continue;
            for (Player p : w.getPlayers()) {
                if (p.getGameMode() == GameMode.SPECTATOR || !z.contains(p.getLocation())) continue;
                // per-zone nodes are not declared in plugin.yml, and undeclared nodes default to true for ops,
                // so they only count when explicitly set
                String zoneNode = "cctv.zone.exempt." + z.name();
                if (p.hasPermission("cctv.alert.exempt") || p.isPermissionSet(zoneNode) && p.hasPermission(zoneNode)) {
                    continue;
                }
                for (Camera c : net) {
                    if (!sees(c, p, w)) continue;
                    String key = c.id() + "|" + z.name() + "|" + p.getUniqueId();
                    Long last = recent.get(key);
                    if (last != null && now - last < cooldownTicks) {
                        c.alert(z.name(), alertTicks); // keep the camera in alert while it still sees them
                        continue;
                    }
                    if (!new CameraAlertEvent(c, p, z.name()).callEvent()) continue;
                    recent.put(key, now);
                    c.alert(z.name(), alertTicks);
                    byGroup.put(z.group(), new Active(c.id(), z.name(), p.getName(), now + alertTicks));
                    notify(c, z, p);
                }
            }
        }
    }

    private boolean sees(Camera c, Player p, World w) {
        Location eye = c.location();
        Vector to = p.getEyeLocation().toVector().subtract(eye.toVector());
        double dist = to.length();
        if (dist > range || dist < 0.1) return false;
        Vector dir = eye.getDirection();
        // a cone a little wider than the vertical FOV covers the horizontal view of a 16:9 feed
        double cos = Math.cos(Math.toRadians(c.fov() * 0.75));
        if (to.clone().normalize().dot(dir) < cos) return false;
        RayTraceResult hit = w.rayTraceBlocks(eye, to.normalize(), dist - 0.3, FluidCollisionMode.NEVER, true);
        return hit == null || hit.getHitBlock() == null;
    }

    private void notify(Camera c, Zone z, Player intruder) {
        var msg = messages.get().get("alert", Messages.p("id", c.id()), Messages.p("name", c.name()),
                Messages.p("zone", z.name()), Messages.p("player", intruder.getName()),
                Messages.p("group", z.group()));
        Sound snd = sound.isBlank() ? null : Sound.sound(Key.key(sound), Sound.Source.MASTER, 1f, 1f);
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean viewer = cameras.viewing(p).map(v -> v.group().equals(z.group())).orElse(false);
            if (!viewer && !p.hasPermission("cctv.alert.receive")) continue;
            p.sendMessage(msg);
            if (snd != null) p.playSound(snd, Sound.Emitter.self());
        }
    }
}

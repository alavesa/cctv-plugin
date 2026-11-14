package fi.alavesa.cctv.monitor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.jspecify.annotations.Nullable;

/** Typed snapshot of the monitors section of config.yml. */
public final class MonitorConfig {

    public enum Mount { WALL, DESK }

    /** A monitor type: its tile grid, how it mounts, and its models. */
    public record Type(String key, Component name, int width, int height, Mount mount, NamespacedKey bezelModel,
                       NamespacedKey placerModel, int style) {
    }

    public final Map<String, Type> types;

    public final int renderScale;
    public final double fps;
    public final double maxDistance;
    public final double watchRadius;
    public final boolean watchNeedsLineOfSight;
    public final double msBudgetPerTick;
    public final int threads;
    public final int cacheTtlTicks;
    public final boolean pushFrames;
    public final double snapshotIntervalSeconds;
    public final int bootTicks;
    public final int switchStaticTicks;
    public final double governorHighMspt;
    public final double governorLowMspt;
    public final int defaultCycleSeconds;
    public final String humSound;
    public final int humIntervalTicks;

    private MonitorConfig(FileConfiguration c, Logger log) {
        String p = "monitors.";
        renderScale = Math.clamp(c.getInt(p + "render.scale", 1), 1, 4);
        fps = Math.clamp(c.getDouble(p + "render.fps", 2.0), 0.1, 10.0);
        maxDistance = Math.clamp(c.getDouble(p + "render.max-distance", 48), 8, 128);
        watchRadius = c.getDouble(p + "render.watch-radius", 16);
        watchNeedsLineOfSight = c.getBoolean(p + "render.watch-needs-line-of-sight", true);
        msBudgetPerTick = Math.max(0.2, c.getDouble(p + "render.ms-budget-per-tick", 2.0));
        threads = Math.clamp(c.getInt(p + "render.threads", 2), 1, 8);
        cacheTtlTicks = Math.max(20, c.getInt(p + "render.chunk-cache-seconds", 8) * 20);
        pushFrames = c.getBoolean(p + "render.push-frames", true);
        snapshotIntervalSeconds = Math.clamp(c.getDouble(p + "snapshot-interval-seconds", 2.0), 1.0, 10.0);
        bootTicks = Math.max(0, (int) (c.getDouble(p + "boot-seconds", 2.0) * 20));
        switchStaticTicks = Math.max(0, c.getInt(p + "switch-static-ticks", 10));
        governorHighMspt = c.getDouble(p + "governor.high-mspt", 45);
        governorLowMspt = c.getDouble(p + "governor.low-mspt", 35);
        defaultCycleSeconds = Math.max(2, c.getInt(p + "default-cycle-seconds", 8));
        humSound = c.getString(p + "hum-sound", "cctv:monitor.hum");
        humIntervalTicks = Math.max(0, c.getInt(p + "hum-interval-ticks", 60));

        Map<String, Type> t = new LinkedHashMap<>();
        ConfigurationSection ts = c.getConfigurationSection(p + "types");
        if (ts != null) {
            for (String k : ts.getKeys(false)) {
                ConfigurationSection s = ts.getConfigurationSection(k);
                if (s == null) continue;
                String key = k.toLowerCase(Locale.ROOT);
                Mount mount;
                try {
                    mount = Mount.valueOf(s.getString("mount", "WALL").toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    log.warning("monitor type " + key + ": mount must be WALL or DESK");
                    continue;
                }
                int w = Math.clamp(s.getInt("width", 1), 1, 8), h = Math.clamp(s.getInt("height", 1), 1, 8);
                if (mount == Mount.DESK && (w != 1 || h != 1)) {
                    log.warning("monitor type " + key + ": DESK monitors are 1x1; size ignored");
                    w = h = 1;
                }
                NamespacedKey bezel = key(s.getString("model.bezel", "cctv:monitor_tile"));
                NamespacedKey placer = key(s.getString("model.placer", bezel == null ? "" : bezel.asString()));
                t.put(key, new Type(key, MiniMessage.miniMessage().deserialize(s.getString("name", key)), w, h, mount,
                        bezel, placer == null ? bezel : placer, style(s.getString("style", "color"))));
            }
        }
        types = Collections.unmodifiableMap(t);
    }

    public static MonitorConfig load(FileConfiguration c, Logger log) {
        return new MonitorConfig(c, log);
    }

    /** color | green | amber | thermal, as the style code the CRT shader reads. */
    static int style(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "green" -> 1;
            case "amber" -> 2;
            case "thermal" -> 3;
            default -> 0;
        };
    }

    private static @Nullable NamespacedKey key(@Nullable String s) {
        return s == null || s.isBlank() ? null : NamespacedKey.fromString(s.toLowerCase(Locale.ROOT));
    }
}

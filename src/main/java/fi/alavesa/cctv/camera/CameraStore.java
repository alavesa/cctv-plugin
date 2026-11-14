package fi.alavesa.cctv.camera;

import fi.alavesa.cctv.api.CameraType;
import fi.alavesa.cctv.api.Mount;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * cameras.yml persistence. The YAML text is built on the main thread (cheap, a few KB) and written on a single
 * background thread through a temp file + atomic move, so a crash mid-write never leaves a truncated file.
 */
final class CameraStore {

    record Loaded(List<Camera> cameras, int nextId) {
    }

    private final Path file;
    private final Logger log;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cctv-io");
        t.setDaemon(true);
        return t;
    });

    CameraStore(Path file, Logger log) {
        this.file = file;
        this.log = log;
    }

    Loaded load(Function<String, CameraType> typeResolver, double damagedThreshold) {
        List<Camera> out = new ArrayList<>();
        if (!Files.exists(file)) return new Loaded(out, 1);
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException e) {
            // keep the broken file for inspection; starting empty would overwrite it on the next save
            Path backup = file.resolveSibling("cameras.yml.broken-" + System.currentTimeMillis());
            try {
                Files.copy(file, backup);
            } catch (IOException ignored) {
                // best effort
            }
            log.log(Level.SEVERE, "cameras.yml could not be read; a copy was saved as " + backup.getFileName(), e);
            return new Loaded(out, 1);
        }
        ConfigurationSection cams = y.getConfigurationSection("cameras");
        if (cams != null) {
            for (String id : cams.getKeys(false)) {
                ConfigurationSection s = cams.getConfigurationSection(id);
                if (s == null) continue;
                try {
                    out.add(read(id, s, typeResolver, damagedThreshold));
                } catch (RuntimeException e) {
                    log.warning("camera '" + id + "' in cameras.yml is invalid and was skipped: " + e.getMessage());
                }
            }
        }
        return new Loaded(out, Math.max(1, y.getInt("next-id", out.size() + 1)));
    }

    private static Camera read(String id, ConfigurationSection s, Function<String, CameraType> types, double thr) {
        String typeKey = s.getString("type", "dome");
        String world = s.getString("world");
        if (world == null) throw new IllegalArgumentException("no world");
        Camera c = new Camera(id, typeKey, types.apply(typeKey), world,
                s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                Mount.valueOf(s.getString("mount", "WALL").toUpperCase(Locale.ROOT)),
                (float) s.getDouble("base-yaw"), (float) s.getDouble("base-pitch"));
        c.damagedThreshold = thr;
        c.name = s.getString("name", id);
        c.group = s.getString("group", "default");
        c.fov = (float) s.getDouble("fov", c.fov);
        c.panMin = (float) s.getDouble("pan-min", c.panMin);
        c.panMax = (float) s.getDouble("pan-max", c.panMax);
        c.tiltMin = (float) s.getDouble("tilt-min", c.tiltMin);
        c.tiltMax = (float) s.getDouble("tilt-max", c.tiltMax);
        c.pan = Math.clamp((float) s.getDouble("pan", 0), c.panMin, c.panMax);
        c.tilt = Math.clamp((float) s.getDouble("tilt", 0), c.tiltMin, c.tiltMax);
        c.targetPan = c.pan;
        c.targetTilt = c.tilt;
        c.health = Math.clamp(s.getDouble("health", c.health), 0, c.type.maxHealth());
        c.powered = s.getBoolean("powered", true);
        c.enabled = s.getBoolean("enabled", true);
        c.clearance = s.getInt("clearance", c.clearance);
        return c;
    }

    /** Serialises on the calling (main) thread. */
    String serialise(Collection<Camera> cameras, int nextId) {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("CCTV cameras. Edit with the server stopped, or use /cctv commands."));
        y.set("next-id", nextId);
        for (Camera c : cameras) {
            String p = "cameras." + c.id + ".";
            y.set(p + "name", c.name);
            y.set(p + "group", c.group);
            y.set(p + "type", c.typeKey);
            y.set(p + "world", c.world);
            y.set(p + "x", c.x);
            y.set(p + "y", c.y);
            y.set(p + "z", c.z);
            y.set(p + "mount", c.mount.name());
            y.set(p + "base-yaw", (double) c.baseYaw);
            y.set(p + "base-pitch", (double) c.basePitch);
            y.set(p + "pan", (double) c.pan);
            y.set(p + "tilt", (double) c.tilt);
            y.set(p + "fov", (double) c.fov);
            y.set(p + "pan-min", (double) c.panMin);
            y.set(p + "pan-max", (double) c.panMax);
            y.set(p + "tilt-min", (double) c.tiltMin);
            y.set(p + "tilt-max", (double) c.tiltMax);
            y.set(p + "health", c.health);
            y.set(p + "powered", c.powered);
            y.set(p + "enabled", c.enabled);
            y.set(p + "clearance", c.clearance);
        }
        return y.saveToString();
    }

    void saveAsync(String yaml) {
        io.execute(() -> write(yaml));
    }

    /** Flushes pending async writes, then writes synchronously (used on disable). */
    void saveNowAndClose(String yaml) {
        io.shutdown();
        try {
            if (!io.awaitTermination(5, TimeUnit.SECONDS)) log.warning("cctv-io did not finish in 5 s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        write(yaml);
    }

    private void write(String yaml) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, yaml, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.log(Level.SEVERE, "could not save cameras.yml", e);
        }
    }

}

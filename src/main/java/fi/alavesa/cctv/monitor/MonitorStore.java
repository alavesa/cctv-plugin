package fi.alavesa.cctv.monitor;

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
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** monitors.yml, written like cameras.yml: built on the main thread, written atomically on a background thread. */
final class MonitorStore {

    private final Path file;
    private final Logger log;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cctv-monitor-io");
        t.setDaemon(true);
        return t;
    });

    MonitorStore(Path file, Logger log) {
        this.file = file;
        this.log = log;
    }

    /** released: map ids of removed monitors whose files are deleted again after saves ({@link MapFiles}) */
    record Loaded(List<Monitor> monitors, int nextId, List<Integer> released) {
    }

    Loaded load(Map<String, MonitorConfig.Type> types) {
        List<Monitor> out = new ArrayList<>();
        if (!Files.exists(file) || types.isEmpty()) return new Loaded(out, 1, List.of());
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file.toFile());
        ConfigurationSection ms = y.getConfigurationSection("monitors");
        if (ms != null) {
            for (String id : ms.getKeys(false)) {
                ConfigurationSection s = ms.getConfigurationSection(id);
                if (s == null) continue;
                try {
                    String typeKey = s.getString("type", "");
                    MonitorConfig.Type type = types.getOrDefault(typeKey, types.values().iterator().next());
                    Monitor m = new Monitor(id, typeKey, type, s.getString("world"), s.getInt("x"), s.getInt("y"),
                            s.getInt("z"), BlockFace.valueOf(s.getString("facing", "SOUTH").toUpperCase(Locale.ROOT)));
                    m.cameras.addAll(s.getStringList("cameras"));
                    m.cycleSeconds = s.getInt("cycle-seconds", 0);
                    m.mode = Monitor.Mode.valueOf(s.getString("mode", "LIVE").toUpperCase(Locale.ROOT));
                    m.powered = s.getBoolean("powered", true);
                    List<Integer> ids = s.getIntegerList("map-ids");
                    for (int i = 0; i < Math.min(ids.size(), m.mapIds.length); i++) m.mapIds[i] = ids.get(i);
                    out.add(m);
                } catch (RuntimeException e) {
                    log.warning("monitor '" + id + "' in monitors.yml is invalid and was skipped: " + e.getMessage());
                }
            }
        }
        return new Loaded(out, Math.max(1, y.getInt("next-id", out.size() + 1)), y.getIntegerList("released-maps"));
    }

    String serialise(Collection<Monitor> monitors, int nextId, Collection<Integer> released) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-id", nextId);
        if (!released.isEmpty()) y.set("released-maps", new ArrayList<>(released));
        for (Monitor m : monitors) {
            String p = "monitors." + m.id + ".";
            y.set(p + "type", m.typeKey);
            y.set(p + "world", m.world);
            y.set(p + "x", m.bx);
            y.set(p + "y", m.by);
            y.set(p + "z", m.bz);
            y.set(p + "facing", m.facing.name());
            y.set(p + "cameras", m.cameras);
            y.set(p + "cycle-seconds", m.cycleSeconds);
            y.set(p + "mode", m.mode.name());
            y.set(p + "powered", m.powered);
            List<Integer> ids = new ArrayList<>();
            for (int id : m.mapIds) ids.add(id);
            y.set(p + "map-ids", ids);
        }
        return y.saveToString();
    }

    void saveAsync(String yaml) {
        io.execute(() -> write(yaml));
    }

    void saveNowAndClose(String yaml) {
        io.shutdown();
        try {
            io.awaitTermination(5, TimeUnit.SECONDS);
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
            log.log(Level.SEVERE, "could not save monitors.yml", e);
        }
    }
}

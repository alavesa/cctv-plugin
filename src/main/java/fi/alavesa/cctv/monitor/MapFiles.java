package fi.alavesa.cctv.monitor;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.jspecify.annotations.Nullable;

/**
 * Deletes the map files (&lt;save&gt;/data/minecraft/maps/&lt;id&gt;.dat; data/map_&lt;id&gt;.dat on older layouts) of monitors
 * that were removed. The server may still hold such a map in memory and write it again at the next world save, so
 * released ids stay in a ledger (monitors.yml {@code released-maps}): their files are deleted again right after
 * every world save and once more at startup, before anything can load them.
 *
 * <p>{@link #orphans} finds files left by older versions: maps that look exactly like CCTV screens (locked, position
 * tracking off, no pixels saved, no banners or frames; CCTV pictures only ever live in the plugin's renderer) and that
 * no monitor uses. They are only listed, and deleted on an explicit {@code /cctv monitor cleanmaps confirm}.
 */
final class MapFiles {

    private final Logger log;
    private final Set<Integer> released = new LinkedHashSet<>();

    MapFiles(Logger log) {
        this.log = log;
    }

    Collection<Integer> released() {
        return released;
    }

    /** Startup: the ledger from monitors.yml; files still there are deleted unless a monitor uses the id again. */
    void load(Collection<Integer> ledger, Set<Integer> inUse) {
        released.clear();
        for (int id : ledger) if (!inUse.contains(id)) released.add(id);
        sweep();
        released.removeIf(id -> !exists(id)); // gone for good: nothing loads them before a save at startup
    }

    /** A removed monitor's maps: blank them now, delete their files, remember them for the next sweeps. */
    @SuppressWarnings("deprecation") // Bukkit.getMap(int)
    void release(int[] ids) {
        for (int id : ids) {
            if (id < 0) continue;
            MapView v = Bukkit.getMap(id);
            if (v != null) for (MapRenderer r : List.copyOf(v.getRenderers())) v.removeRenderer(r);
            released.add(id);
        }
        sweep();
    }

    /** A new monitor took this id (the server recycled it): it is in use again. */
    void reuse(int id) {
        released.remove(id);
    }

    /** Deletes the files of every released id. Main thread; cheap (a few file deletes). */
    void sweep() {
        for (int id : released) {
            for (Path p : paths(id)) {
                try {
                    if (Files.deleteIfExists(p)) log.fine("deleted unused map file " + p);
                } catch (IOException e) {
                    log.warning("could not delete unused map file " + p + ": " + e.getMessage());
                }
            }
        }
    }

    /** Map files that look like CCTV screens and that no monitor uses (ids, ascending). */
    List<Integer> orphans(Set<Integer> inUse) {
        List<Integer> out = new ArrayList<>();
        Path dir = dir();
        if (dir == null || !Files.isDirectory(dir)) return out;
        try (var files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String n = p.getFileName().toString();
                if (!n.matches("\\d+\\.dat")) continue;
                int id = Integer.parseInt(n.substring(0, n.length() - 4));
                if (!inUse.contains(id) && looksLikeScreen(p)) out.add(id);
            }
        } catch (IOException e) {
            log.warning("could not list " + dir + ": " + e.getMessage());
        }
        out.sort(null);
        return out;
    }

    /** Deletes the given orphan files now (and keeps them in the ledger in case the server saves one again). */
    int deleteOrphans(List<Integer> ids) {
        int before = 0;
        for (int id : ids) if (exists(id)) before++;
        released.addAll(ids);
        sweep();
        int left = 0;
        for (int id : ids) if (exists(id)) left++;
        return before - left;
    }

    private boolean exists(int id) {
        for (Path p : paths(id)) if (Files.exists(p)) return true;
        return false;
    }

    private static List<Path> paths(int id) {
        Path root = root();
        if (root == null) return List.of();
        Path data = root.resolve("data");
        return List.of(data.resolve("minecraft").resolve("maps").resolve(id + ".dat"), data.resolve("map_" + id + ".dat"));
    }

    private static @Nullable Path dir() {
        Path root = root();
        return root == null ? null : root.resolve("data").resolve("minecraft").resolve("maps");
    }

    /**
     * The save's root folder (where level.dat is). Since 26.x, {@code World.getWorldFolder()} of the overworld is
     * world/dimensions/minecraft/overworld, while maps are stored under the root: world/data/minecraft/maps.
     */
    private static @Nullable Path root() {
        World w = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst();
        if (w == null) return null;
        for (Path p = w.getWorldFolder().toPath().toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("level.dat"))) return p;
        }
        return Bukkit.getWorldContainer().toPath().resolve(w.getName());
    }

    // ---- a minimal reader for the few fields of a map file ------------------------------------------------------

    /** locked, trackingPosition 0, every colour byte 0, no banners/frames. Unreadable files are never matched. */
    private static boolean looksLikeScreen(Path p) {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(p));
             DataInputStream d = new DataInputStream(in)) {
            if (d.readByte() != 10) return false;
            d.skipBytes(d.readUnsignedShort());
            Fields f = new Fields();
            compound(d, f, false);
            return f.data && f.locked && !f.tracking && f.colorsSeen && f.colorsBlank && !f.markers;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static final class Fields {
        boolean data, locked, tracking = true, colorsSeen, colorsBlank, markers;
    }

    private static void compound(DataInputStream d, Fields f, boolean inData) throws IOException {
        while (true) {
            int type = d.readByte();
            if (type == 0) return;
            String name = d.readUTF();
            if (!inData && type == 10 && name.equals("data")) {
                f.data = true;
                compound(d, f, true);
                continue;
            }
            if (inData && type == 1 && name.equals("locked")) {
                f.locked = d.readByte() != 0;
                continue;
            }
            if (inData && type == 1 && name.equals("trackingPosition")) {
                f.tracking = d.readByte() != 0;
                continue;
            }
            if (inData && type == 7 && name.equals("colors")) {
                int n = d.readInt();
                boolean blank = true;
                for (int i = 0; i < n; i++) if (d.readByte() != 0) blank = false;
                f.colorsSeen = true;
                f.colorsBlank = blank;
                continue;
            }
            if (inData && type == 9 && (name.equals("banners") || name.equals("frames"))) {
                int et = d.readByte(), n = d.readInt();
                if (n > 0) f.markers = true;
                for (int i = 0; i < n; i++) skip(d, et);
                continue;
            }
            skip(d, type);
        }
    }

    private static void skip(DataInputStream d, int type) throws IOException {
        switch (type) {
            case 1 -> d.skipBytes(1);
            case 2 -> d.skipBytes(2);
            case 3, 5 -> d.skipBytes(4);
            case 4, 6 -> d.skipBytes(8);
            case 7 -> d.skipBytes(d.readInt());
            case 8 -> d.skipBytes(d.readUnsignedShort());
            case 9 -> {
                int et = d.readByte(), n = d.readInt();
                for (int i = 0; i < n; i++) skip(d, et);
            }
            case 10 -> {
                while (true) {
                    int t = d.readByte();
                    if (t == 0) break;
                    d.skipBytes(d.readUnsignedShort());
                    skip(d, t);
                }
            }
            case 11 -> d.skipBytes(d.readInt() * 4);
            case 12 -> d.skipBytes(d.readInt() * 8);
            default -> throw new IOException("bad NBT tag " + type);
        }
    }
}

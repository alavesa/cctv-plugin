package fi.alavesa.cctv.monitor;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.jspecify.annotations.Nullable;

/**
 * Chunk snapshots for the feed renderer, captured on the main thread and shared read-only with render threads
 * (ChunkSnapshot is immutable). Entries expire after a TTL and are dropped early when a block changes in the chunk.
 * Unloaded chunks are never loaded for a feed.
 */
final class SnapshotCache {

    private record Entry(ChunkSnapshot snapshot, long capturedAt) {
    }

    private final Map<String, Map<Long, Entry>> worlds = new HashMap<>();
    private int ttl;
    long hits;
    long misses;
    double captureMs;

    SnapshotCache(int ttlTicks) {
        this.ttl = ttlTicks;
    }

    void ttl(int ttlTicks) {
        this.ttl = ttlTicks;
    }

    /** A fresh snapshot, or null if it must be captured first. */
    @Nullable ChunkSnapshot fresh(World w, int cx, int cz, long now) {
        Map<Long, Entry> m = worlds.get(w.getName());
        Entry e = m == null ? null : m.get(Chunk.getChunkKey(cx, cz));
        if (e != null && now - e.capturedAt < ttl) {
            hits++;
            return e.snapshot;
        }
        misses++;
        return null;
    }

    /** Captures (main thread). Returns null for unloaded chunks. */
    @Nullable ChunkSnapshot capture(World w, int cx, int cz, long now) {
        if (!w.isChunkLoaded(cx, cz)) return null;
        long t0 = System.nanoTime();
        ChunkSnapshot s = w.getChunkAt(cx, cz).getChunkSnapshot(false, false, false);
        captureMs = captureMs * 0.9 + (System.nanoTime() - t0) / 1e6 * 0.1;
        worlds.computeIfAbsent(w.getName(), k -> new HashMap<>()).put(Chunk.getChunkKey(cx, cz), new Entry(s, now));
        return s;
    }

    void invalidate(World w, int cx, int cz) {
        Map<Long, Entry> m = worlds.get(w.getName());
        if (m != null) m.remove(Chunk.getChunkKey(cx, cz));
    }

    /** Drops expired entries (call occasionally). */
    void sweep(long now) {
        for (Map<Long, Entry> m : worlds.values()) m.values().removeIf(e -> now - e.capturedAt >= ttl * 2L);
    }

    int size() {
        int n = 0;
        for (Map<Long, Entry> m : worlds.values()) n += m.size();
        return n;
    }
}

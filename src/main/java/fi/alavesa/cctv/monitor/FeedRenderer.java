package fi.alavesa.cctv.monitor;

import java.util.Map;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;

/**
 * Off-main-thread renderer: one ray per output pixel through immutable chunk snapshots (Amanatides-Woo voxel walk).
 * A hit block gets its vanilla map colour, shaded by face direction, block/sky light and distance; entities are flat
 * silhouettes coloured by category; nothing else (no name tags, no particles, no textures). Output is map palette
 * bytes, so it goes straight onto map tiles. Pure function of its {@link Job}: no Bukkit calls, no shared state.
 */
public final class FeedRenderer {

    /** One camera pose. look: 0 standard, 1 night vision (amplified), 2 thermal (entities hot, world cold). */
    public record Pose(double x, double y, double z, float yaw, float pitch, float fov, int look) {
    }

    /** Entity bounding box and silhouette base colour. */
    public record Ent(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int base) {
    }

    /**
     * @param poses   1 pose (full screen) or 4 (quad split, row-major from the top left)
     * @param ents    entities per pose
     * @param chunks  chunk key ({@code Chunk.getChunkKey}) to snapshot; missing chunks are see-through
     */
    public record Job(int width, int height, Pose[] poses, Ent[][] ents, Map<Long, ChunkSnapshot> chunks, int minY,
                      int maxY, float daylight, double maxDistance) {
    }

    private final BlockColors colors;

    public FeedRenderer(BlockColors colors) {
        this.colors = colors;
    }

    public byte[] render(Job job) {
        byte[] out = new byte[job.width * job.height];
        if (job.poses.length == 4) {
            int hw = job.width / 2, hh = job.height / 2;
            for (int q = 0; q < 4; q++) {
                viewport(job, q, (q % 2) * hw, (q / 2) * hh, hw, hh, out);
            }
            // thin dividers between the quadrants
            for (int x = 0; x < job.width; x++) out[hh * job.width + x] = Screen.BACKGROUND;
            for (int y = 0; y < job.height; y++) out[y * job.width + hw] = Screen.BACKGROUND;
        } else {
            viewport(job, 0, 0, 0, job.width, job.height, out);
        }
        return out;
    }

    private void viewport(Job job, int idx, int ox, int oy, int w, int h, byte[] out) {
        Pose p = job.poses[idx];
        Ent[] ents = job.ents[idx];
        double yaw = Math.toRadians(p.yaw), pitch = Math.toRadians(p.pitch);
        // forward, right, up of the camera (Minecraft: yaw 0 = +Z, positive pitch = down)
        double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
        double rx = -fz, rz = fx;
        double rl = Math.sqrt(rx * rx + rz * rz);
        if (rl < 1e-6) {
            rx = -Math.cos(yaw);
            rz = -Math.sin(yaw);
            rl = 1;
        }
        rx /= rl;
        rz /= rl;
        double ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy; // up = cross(right, forward)
        double tanY = Math.tan(Math.toRadians(p.fov) / 2), tanX = tanY * w / h;
        Walker walker = new Walker(job, colors);
        for (int py = 0; py < h; py++) {
            double sy = (1 - 2 * (py + 0.5) / h) * tanY;
            for (int px = 0; px < w; px++) {
                double sx = (2 * (px + 0.5) / w - 1) * tanX;
                double dx = fx + rx * sx + ux * sy, dy = fy + uy * sy, dz = fz + rz * sx + uz * sy;
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                out[(oy + py) * job.width + ox + px] = walker.trace(p, ents, dx / len, dy / len, dz / len);
            }
        }
    }

    /** Per-viewport state (cached chunk lookup). Not shared between threads. */
    private static final class Walker {
        private final Job job;
        private final BlockColors colors;
        private long lastKey = Long.MIN_VALUE;
        private ChunkSnapshot last;

        Walker(Job job, BlockColors colors) {
            this.job = job;
            this.colors = colors;
        }

        private ChunkSnapshot chunk(int x, int z) {
            long key = ((long) (x >> 4) & 0xffffffffL) | (((long) (z >> 4) & 0xffffffffL) << 32);
            if (key != lastKey) {
                lastKey = key;
                last = job.chunks.get(key);
            }
            return last;
        }

        byte trace(Pose p, Ent[] ents, double dx, double dy, double dz) {
            double max = job.maxDistance;
            // nearest entity along the ray
            double entT = max;
            int entBase = 0;
            for (Ent e : ents) {
                double t = slab(p, dx, dy, dz, e);
                if (t >= 0 && t < entT) {
                    entT = t;
                    entBase = e.base;
                }
            }
            int x = (int) Math.floor(p.x), y = (int) Math.floor(p.y), z = (int) Math.floor(p.z);
            int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
            double tdx = dx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx);
            double tdy = dy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy);
            double tdz = dz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
            double tmx = dx == 0 ? Double.MAX_VALUE : (dx > 0 ? x + 1 - p.x : p.x - x) * tdx;
            double tmy = dy == 0 ? Double.MAX_VALUE : (dy > 0 ? y + 1 - p.y : p.y - y) * tdy;
            double tmz = dz == 0 ? Double.MAX_VALUE : (dz > 0 ? z + 1 - p.z : p.z - z) * tdz;
            int px = x, py = y, pz = z, face = 1;
            double t = 0;
            while (true) {
                // step to the next voxel (the camera's own voxel is skipped)
                px = x;
                py = y;
                pz = z;
                if (tmx < tmy && tmx < tmz) {
                    x += stepX;
                    t = tmx;
                    tmx += tdx;
                    face = 0;
                } else if (tmy < tmz) {
                    y += stepY;
                    t = tmy;
                    tmy += tdy;
                    face = stepY > 0 ? 3 : 1;
                } else {
                    z += stepZ;
                    t = tmz;
                    tmz += tdz;
                    face = 2;
                }
                if (t >= entT) return entity(p, entBase, entT);
                if (t > max) return sky(p);
                if (y >= job.maxY) {
                    if (stepY > 0) return sky(p);
                    continue;
                }
                if (y < job.minY) return Screen.BACKGROUND;
                ChunkSnapshot c = chunk(x, z);
                if (c == null) continue;
                Material m = c.getBlockType(x & 15, y, z & 15);
                int base = colors.of(m);
                if (base == 0) continue;
                return shade(p, base, light(px, py, pz), face, t);
            }
        }

        private float light(int x, int y, int z) {
            if (y < job.minY || y >= job.maxY) return job.daylight;
            ChunkSnapshot c = chunk(x, z);
            if (c == null) return job.daylight;
            float sky = c.getBlockSkyLight(x & 15, y, z & 15) / 15f * job.daylight;
            float block = c.getBlockEmittedLight(x & 15, y, z & 15) / 15f;
            return Math.max(Math.max(sky, block), 0.06f);
        }

        private byte shade(Pose p, int base, float light, int face, double t) {
            // face: 0 x-side, 1 top, 2 z-side, 3 bottom
            float faceF = switch (face) {
                case 1 -> 1f;
                case 0 -> 0.86f;
                case 2 -> 0.76f;
                default -> 0.6f;
            };
            float b = light * faceF * (float) (1 - 0.45 * t / job.maxDistance);
            if (p.look == 1) b = Math.min(1f, b * 2.6f + 0.2f);
            if (p.look == 2) b *= 0.55f; // thermal: the world is "cold"
            return quantise(base, b);
        }

        private byte entity(Pose p, int base, double t) {
            if (p.look == 2) return MapColors.pixel(MapColors.SNOW, 2); // thermal: bodies glow
            float b = (float) (1 - 0.35 * t / job.maxDistance);
            if (p.look == 1) b = 1f;
            return quantise(base, b);
        }

        private byte sky(Pose p) {
            float d = job.daylight;
            if (p.look == 2) return MapColors.pixel(MapColors.BLACK, 3);
            if (p.look == 1) return quantise(MapColors.GRAY, Math.min(1f, d + 0.3f));
            return d < 0.3f ? MapColors.pixel(MapColors.BLACK, 2) : quantise(MapColors.PALE_BLUE, d);
        }

        /** Brightness 0..1 to one of the four shades of a base colour, falling to black when dark. */
        private static byte quantise(int base, float b) {
            if (b >= 0.85f) return MapColors.pixel(base, 2);
            if (b >= 0.68f) return MapColors.pixel(base, 1);
            if (b >= 0.5f) return MapColors.pixel(base, 0);
            if (b >= 0.3f) return MapColors.pixel(base, 3);
            return MapColors.pixel(MapColors.BLACK, b >= 0.15f ? 2 : 3);
        }

        private static double slab(Pose p, double dx, double dy, double dz, Ent e) {
            double t0 = 0, t1 = Double.MAX_VALUE;
            double[] o = {p.x, p.y, p.z}, d = {dx, dy, dz}, lo = {e.minX, e.minY, e.minZ}, hi = {e.maxX, e.maxY, e.maxZ};
            for (int i = 0; i < 3; i++) {
                if (Math.abs(d[i]) < 1e-9) {
                    if (o[i] < lo[i] || o[i] > hi[i]) return -1;
                    continue;
                }
                double a = (lo[i] - o[i]) / d[i], b = (hi[i] - o[i]) / d[i];
                if (a > b) {
                    double s = a;
                    a = b;
                    b = s;
                }
                t0 = Math.max(t0, a);
                t1 = Math.min(t1, b);
                if (t0 > t1) return -1;
            }
            return t0;
        }
    }
}

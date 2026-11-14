package fi.alavesa.cctv.monitor;

import java.util.Map;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;

/**
 * Off-main-thread renderer: one ray per output pixel through immutable chunk snapshots (Amanatides-Woo voxel walk).
 *
 * <p>Shading happens in RGB, then {@link Quantizer} dithers into the map palette:
 * <ul>
 *   <li>block colour = the average colour of its real texture for that face (top / side / bottom, see BlockLook);</li>
 *   <li>texture grain: a 16x16 per-face noise pattern scaled by that texture's measured contrast;</li>
 *   <li>Minecraft-style face shading and smooth ambient occlusion at corners and edges;</li>
 *   <li>sky light (scaled by daylight) and block light from the snapshot;</li>
 *   <li>distance fog into a sky gradient; water is translucent with depth, glass slightly tinted;</li>
 *   <li>players, body doubles and Mannequins are player-shaped figures in their skin and armour ({@link Figure});
 *   other entities are shaded boxes by category; no name tags or particles.</li>
 * </ul>
 * Pure function of its {@link Job}: no Bukkit calls, no shared mutable state.
 */
public final class FeedRenderer {

    // ---- tuning -------------------------------------------------------------------------------------------------
    private static final float FACE_TOP = 1.0f, FACE_X = 0.82f, FACE_Z = 0.68f, FACE_BOTTOM = 0.52f;
    private static final float AMBIENT = 0.07f;        // light floor so caves aren't pure black
    private static final float GRAIN = 2.2f;           // texture grain = contrast * this
    private static final float AO_STRENGTH = 0.32f;    // darkening in a fully occluded corner
    private static final float FOG_START = 0.55f;      // fog starts at this fraction of max distance
    private static final float WATER_ALPHA = 0.55f, WATER_DEPTH = 0.07f;
    private static final float[] WATER_RGB = {0.16f, 0.32f, 0.62f};
    private static final float NV_GAIN = 2.6f, NV_FLOOR = 0.22f;

    /** One camera pose. look: 0 standard, 1 night vision (amplified), 2 thermal (entities hot, world cold). */
    public record Pose(double x, double y, double z, float yaw, float pitch, float fov, int look) {
    }

    /** Entity bounding box and silhouette colour (0xRRGGBB); a figure, when set, is drawn instead of the box. */
    public record Ent(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int rgb,
                      @org.jspecify.annotations.Nullable Figure figure) {
    }

    /**
     * @param poses   1 pose (full screen) or 4 (quad split, row-major from the top left)
     * @param ents    entities per pose
     * @param chunks  chunk key ({@code Chunk.getChunkKey}) to snapshot; missing chunks are see-through
     */
    public record Job(int width, int height, Pose[] poses, Ent[][] ents, Map<Long, ChunkSnapshot> chunks, int minY,
                      int maxY, float daylight, double maxDistance) {
    }

    private final BlockLook look;

    FeedRenderer(BlockLook look) {
        this.look = look;
    }

    public byte[] render(Job job) {
        byte[] out = new byte[job.width * job.height];
        if (job.poses.length == 4) {
            int hw = job.width / 2, hh = job.height / 2;
            for (int q = 0; q < 4; q++) viewport(job, q, (q % 2) * hw, (q / 2) * hh, hw, hh, out);
            for (int x = 0; x < job.width; x++) out[hh * job.width + x] = Screen.BACKGROUND;
            for (int y = 0; y < job.height; y++) out[y * job.width + hw] = Screen.BACKGROUND;
        } else {
            viewport(job, 0, 0, 0, job.width, job.height, out);
        }
        return out;
    }

    private void viewport(Job job, int idx, int ox, int oy, int w, int h, byte[] out) {
        Pose p = job.poses[idx];
        double yaw = Math.toRadians(p.yaw), pitch = Math.toRadians(p.pitch);
        // forward, right, up (Minecraft: yaw 0 = +Z, positive pitch = down)
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
        double ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;
        double tanY = Math.tan(Math.toRadians(p.fov) / 2), tanX = tanY * w / h;
        Walker walker = new Walker(job, look, p, job.ents[idx]);
        float[] c = new float[3];
        for (int py = 0; py < h; py++) {
            double sy = (1 - 2 * (py + 0.5) / h) * tanY;
            for (int px = 0; px < w; px++) {
                double sx = (2 * (px + 0.5) / w - 1) * tanX;
                double dx = fx + rx * sx + ux * sy, dy = fy + uy * sy, dz = fz + rz * sx + uz * sy;
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                walker.trace(dx / len, dy / len, dz / len, c);
                out[(oy + py) * job.width + ox + px] = Quantizer.quantise(c[0], c[1], c[2], ox + px, oy + py);
            }
        }
    }

    /** Per-viewport ray walker (cached chunk lookup). Not shared between threads. */
    private static final class Walker {
        private final Job job;
        private final BlockLook look;
        private final Pose p;
        private final Ent[] ents;
        private long lastKey = Long.MIN_VALUE;
        private ChunkSnapshot last;
        private final float[] sky = new float[3]; // scratch, reused for every pixel
        private final Figure.Hit fig = new Figure.Hit();

        Walker(Job job, BlockLook look, Pose p, Ent[] ents) {
            this.job = job;
            this.look = look;
            this.p = p;
            this.ents = ents;
        }

        private ChunkSnapshot chunk(int x, int z) {
            long key = ((long) (x >> 4) & 0xffffffffL) | (((long) (z >> 4) & 0xffffffffL) << 32);
            if (key != lastKey) {
                lastKey = key;
                last = job.chunks.get(key);
            }
            return last;
        }

        private Material type(int x, int y, int z) {
            if (y < job.minY || y >= job.maxY) return Material.AIR;
            ChunkSnapshot c = chunk(x, z);
            return c == null ? Material.AIR : c.getBlockType(x & 15, y, z & 15);
        }

        private boolean solid(int x, int y, int z) {
            return look.kind(type(x, y, z)) == BlockLook.OPAQUE;
        }

        void trace(double dx, double dy, double dz, float[] out) {
            double max = job.maxDistance;
            double entT = max;
            Ent ent = null;
            int entAxis = 1, entRgb = 0;
            for (Ent e : ents) {
                double[] hit = slab(dx, dy, dz, e);
                if (hit == null || hit[0] >= entT) continue;
                if (e.figure() == null) {
                    entT = hit[0];
                    entAxis = (int) hit[1];
                    entRgb = e.rgb();
                } else if (e.figure().hit(p.x, p.y, p.z, dx, dy, dz, entT, fig)) {
                    entT = fig.t;
                    entAxis = fig.axis;
                    entRgb = fig.rgb;
                } else {
                    continue;
                }
                ent = e;
            }
            int x = (int) Math.floor(p.x), y = (int) Math.floor(p.y), z = (int) Math.floor(p.z);
            int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
            double tdx = dx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx);
            double tdy = dy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy);
            double tdz = dz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
            double tmx = dx == 0 ? Double.MAX_VALUE : (dx > 0 ? x + 1 - p.x : p.x - x) * tdx;
            double tmy = dy == 0 ? Double.MAX_VALUE : (dy > 0 ? y + 1 - p.y : p.y - y) * tdy;
            double tmz = dz == 0 ? Double.MAX_VALUE : (dz > 0 ? z + 1 - p.z : p.z - z) * tdz;
            float tr = 1, tg = 1, tb = 1;       // transmission through glass / water so far
            float wr = 0, wg = 0, wb = 0;       // light added by water layers
            double waterIn = -1;
            int px, py, pz, axis;
            double t;
            while (true) {
                px = x;
                py = y;
                pz = z;
                if (tmx < tmy && tmx < tmz) {
                    x += stepX;
                    t = tmx;
                    tmx += tdx;
                    axis = 0;
                } else if (tmy < tmz) {
                    y += stepY;
                    t = tmy;
                    tmy += tdy;
                    axis = 1;
                } else {
                    z += stepZ;
                    t = tmz;
                    tmz += tdz;
                    axis = 2;
                }
                if (ent != null && t >= entT) {
                    entity(ent, entAxis, entRgb, dy, entT, out);
                    break;
                }
                if (t > max) {
                    sky(dy, out);
                    break;
                }
                if (y >= job.maxY && stepY > 0) {
                    sky(dy, out);
                    break;
                }
                if (y < job.minY) {
                    out[0] = out[1] = out[2] = 0.02f;
                    break;
                }
                Material m = type(x, y, z);
                int k = look.kind(m);
                if (k == BlockLook.AIR) {
                    if (waterIn >= 0) waterIn = -1;
                    continue;
                }
                if (k == BlockLook.WATER) {
                    if (waterIn < 0) {
                        // surface: some reflection of the sky-ish water colour, then see through with depth
                        waterIn = t;
                        float l = light(px, py, pz);
                        wr += WATER_RGB[0] * l * WATER_ALPHA * tr;
                        wg += WATER_RGB[1] * l * WATER_ALPHA * tg;
                        wb += WATER_RGB[2] * l * WATER_ALPHA * tb;
                        tr *= 1 - WATER_ALPHA;
                        tg *= 1 - WATER_ALPHA * 0.8f;
                        tb *= 1 - WATER_ALPHA * 0.6f;
                    } else {
                        float a = WATER_DEPTH;
                        wr += WATER_RGB[0] * 0.3f * a * tr;
                        wg += WATER_RGB[1] * 0.3f * a * tg;
                        wb += WATER_RGB[2] * 0.3f * a * tb;
                        tr *= 1 - a;
                        tg *= 1 - a;
                        tb *= 1 - a * 0.7f;
                    }
                    if (tr + tg + tb < 0.05f) {
                        out[0] = wr;
                        out[1] = wg;
                        out[2] = wb;
                        break;
                    }
                    continue;
                }
                if (k == BlockLook.GLASS) {
                    tr *= 0.9f;
                    tg *= 0.93f;
                    tb *= 0.95f;
                    continue;
                }
                int face = axis == 1 ? (stepY > 0 ? 3 : 1) : axis == 0 ? 0 : 2;
                shade(m, face, axis, x, y, z, px, py, pz, dx, dy, dz, t, out);
                break;
            }
            out[0] = out[0] * tr + wr;
            out[1] = out[1] * tg + wg;
            out[2] = out[2] * tb + wb;
            if (p.look == 1) nightVision(out);
            else if (p.look == 2 && ent == null) {
                float l = (out[0] + out[1] + out[2]) / 3 * 0.45f;
                out[0] = out[1] = out[2] = l;
            }
        }

        private void shade(Material m, int face, int axis, int x, int y, int z, int px, int py, int pz,
                           double dx, double dy, double dz, double t, float[] out) {
            int rgb = look.rgb(m, face);
            float r = (rgb >> 16 & 255) / 255f, g = (rgb >> 8 & 255) / 255f, b = (rgb & 255) / 255f;
            // hit point on the face, in-face coordinates u, v (0..1)
            double hx = p.x + dx * t, hy = p.y + dy * t, hz = p.z + dz * t;
            double u, v;
            int ua, va; // in-face axes: 0 x, 1 y, 2 z
            if (axis == 0) {
                u = hz - Math.floor(hz);
                v = hy - Math.floor(hy);
                ua = 2;
                va = 1;
            } else if (axis == 1) {
                u = hx - Math.floor(hx);
                v = hz - Math.floor(hz);
                ua = 0;
                va = 2;
            } else {
                u = hx - Math.floor(hx);
                v = hy - Math.floor(hy);
                ua = 0;
                va = 1;
            }
            // texture grain: a stable 16x16 noise pattern per block face
            int tu = (int) (u * 16), tv = (int) (v * 16);
            float n = hash(x * 73856093 ^ y * 19349663 ^ z * 83492791 ^ face * 2654435761L, tu, tv) - 0.5f;
            float grain = 1 + n * look.contrast(m) * GRAIN;
            float faceF = switch (face) {
                case 1 -> FACE_TOP;
                case 0 -> FACE_X;
                case 2 -> FACE_Z;
                default -> FACE_BOTTOM;
            };
            float ao = ao(px, py, pz, ua, va, u, v);
            float l = light(px, py, pz);
            float s = faceF * ao * (AMBIENT + (1 - AMBIENT) * l) * grain;
            float fog = fog(t);
            horizon(sky);
            out[0] = r * s * (1 - fog) + sky[0] * fog;
            out[1] = g * s * (1 - fog) + sky[1] * fog;
            out[2] = b * s * (1 - fog) + sky[2] * fog;
        }

        /** Smooth ambient occlusion of the face in front of voxel (px,py,pz), bilinear over its four corners. */
        private float ao(int px, int py, int pz, int ua, int va, double u, double v) {
            float c00 = corner(px, py, pz, ua, va, -1, -1), c10 = corner(px, py, pz, ua, va, 1, -1);
            float c01 = corner(px, py, pz, ua, va, -1, 1), c11 = corner(px, py, pz, ua, va, 1, 1);
            float a = (float) ((c00 * (1 - u) + c10 * u) * (1 - v) + (c01 * (1 - u) + c11 * u) * v);
            return 1 - AO_STRENGTH * (1 - a);
        }

        /** 1 = open corner, 0 = fully occluded (Minecraft's smooth-lighting rule). */
        private float corner(int x, int y, int z, int ua, int va, int su, int sv) {
            int ax = ua == 0 ? su : 0, ay = ua == 1 ? su : 0, az = ua == 2 ? su : 0;
            int bx = va == 0 ? sv : 0, by = va == 1 ? sv : 0, bz = va == 2 ? sv : 0;
            boolean s1 = solid(x + ax, y + ay, z + az), s2 = solid(x + bx, y + by, z + bz);
            if (s1 && s2) return 0;
            boolean c = solid(x + ax + bx, y + ay + by, z + az + bz);
            return (3 - ((s1 ? 1 : 0) + (s2 ? 1 : 0) + (c ? 1 : 0))) / 3f;
        }

        private float light(int x, int y, int z) {
            if (y < job.minY || y >= job.maxY) return job.daylight;
            ChunkSnapshot c = chunk(x, z);
            if (c == null) return job.daylight;
            float sky = c.getBlockSkyLight(x & 15, y, z & 15) / 15f * job.daylight;
            float block = c.getBlockEmittedLight(x & 15, y, z & 15) / 15f * 0.95f;
            float l = Math.max(sky, block);
            return l * l * 0.35f + l * 0.65f; // a little contrast, like the game's light curve
        }

        private float fog(double t) {
            double f = Math.max(0, (t / job.maxDistance - FOG_START) / (1 - FOG_START)); // clamp BEFORE squaring
            return (float) Math.min(1, f * f);
        }

        private void horizon(float[] out) {
            float d = job.daylight;
            out[0] = 0.70f * d + 0.02f;
            out[1] = 0.80f * d + 0.03f;
            out[2] = 0.95f * d + 0.06f;
        }

        private void sky(double dy, float[] out) {
            float d = job.daylight;
            float up = (float) Math.clamp(dy, 0, 1);
            if (dy < 0) up = 0;
            float zr = 0.32f, zg = 0.50f, zb = 0.92f;   // zenith
            float hr = 0.70f, hg = 0.80f, hb = 0.95f;   // horizon
            out[0] = (hr + (zr - hr) * up) * d + 0.02f;
            out[1] = (hg + (zg - hg) * up) * d + 0.03f;
            out[2] = (hb + (zb - hb) * up) * d + 0.06f;
        }

        private void entity(Ent e, int axis, int rgb, double dy, double t, float[] out) {
            if (p.look == 2) { // thermal: bodies glow
                out[0] = out[1] = out[2] = 0.97f;
                return;
            }
            float faceF = axis == 1 ? (dy < 0 ? FACE_TOP : FACE_BOTTOM) : axis == 0 ? FACE_X : FACE_Z;
            float l = light((int) Math.floor((e.minX + e.maxX) / 2), (int) Math.floor(e.maxY),
                    (int) Math.floor((e.minZ + e.maxZ) / 2));
            float s = faceF * (AMBIENT + (1 - AMBIENT) * Math.max(l, 0.35f));
            float fog = fog(t);
            horizon(sky);
            out[0] = (rgb >> 16 & 255) / 255f * s * (1 - fog) + sky[0] * fog;
            out[1] = (rgb >> 8 & 255) / 255f * s * (1 - fog) + sky[1] * fog;
            out[2] = (rgb & 255) / 255f * s * (1 - fog) + sky[2] * fog;
        }

        private void nightVision(float[] c) {
            float l = (c[0] * 0.3f + c[1] * 0.59f + c[2] * 0.11f) * NV_GAIN + NV_FLOOR;
            l = Math.min(1, l);
            c[0] = c[1] = c[2] = l; // the monitor's green phosphor style tints it
        }

        /** Entry distance and hit axis (0 x, 1 y, 2 z) of the ray into a box, or null. */
        private double[] slab(double dx, double dy, double dz, Ent e) {
            double t0 = 0, t1 = Double.MAX_VALUE;
            int axis = 1;
            double[] o = {p.x, p.y, p.z}, d = {dx, dy, dz}, lo = {e.minX, e.minY, e.minZ}, hi = {e.maxX, e.maxY, e.maxZ};
            for (int i = 0; i < 3; i++) {
                if (Math.abs(d[i]) < 1e-9) {
                    if (o[i] < lo[i] || o[i] > hi[i]) return null;
                    continue;
                }
                double a = (lo[i] - o[i]) / d[i], b = (hi[i] - o[i]) / d[i];
                if (a > b) {
                    double s = a;
                    a = b;
                    b = s;
                }
                if (a > t0) {
                    t0 = a;
                    axis = i;
                }
                t1 = Math.min(t1, b);
                if (t0 > t1) return null;
            }
            return new double[]{t0, axis};
        }

        private static float hash(long seed, int u, int v) {
            long h = seed * 0x9E3779B97F4A7C15L + u * 0x632BE59BD9B4E019L + v * 0x85157AF5L;
            h ^= h >>> 29;
            h *= 0xBF58476D1CE4E5B9L;
            h ^= h >>> 32;
            return (h & 0xFFFF) / 65535f;
        }
    }
}

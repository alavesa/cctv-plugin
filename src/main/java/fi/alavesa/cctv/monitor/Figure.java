package fi.alavesa.cctv.monitor;

/**
 * A player-shaped figure on the monitor feed: the six boxes of the player model (head, body, arms, legs) turned to
 * the body yaw and textured from the skin, outer layer included. Built on the main thread, read by render threads.
 *
 * <p>Model space is in skin pixels from the feet: x toward the figure's LEFT hand, y up, z forward. The player model
 * is 32 px tall and drawn at 0.9375 scale (1.875 blocks), like the client.
 *
 * @param x       feet position
 * @param yaw     body yaw, degrees (Minecraft convention: 0 faces +Z)
 * @param skin    64x64 ARGB skin ({@link Skins})
 * @param armour  ARGB of helmet, chestplate, leggings, boots; 0 = none
 */
record Figure(double x, double y, double z, float yaw, int[] skin, int[] armour) {

    static final double SCALE = 0.9375 / 16;
    /** horizontal reach of the arms from the centre, blocks (bounding box for the cheap reject) */
    static final double RADIUS = 9 * SCALE;
    static final double HEIGHT = 32 * SCALE;

    /** x0 y0 z0 x1 y1 z1 | u v | overlay u v | armour slot (head, chest, legs) */
    private static final int[][] PARTS = {
            {-4, 24, -4, 4, 32, 4, 0, 0, 32, 0, 0},     // head + hat
            {-4, 12, -2, 4, 24, 2, 16, 16, 16, 32, 1},  // body + jacket
            {-8, 12, -2, -4, 24, 2, 40, 16, 40, 32, 1}, // right arm + sleeve
            {4, 12, -2, 8, 24, 2, 32, 48, 48, 48, 1},   // left arm + sleeve
            {-4, 0, -2, 0, 12, 2, 0, 16, 0, 32, 2},     // right leg + trousers
            {0, 0, -2, 4, 12, 2, 16, 48, 0, 48, 2},     // left leg + trousers
    };

    /** Result of {@link #hit}: distance, the face's world-ish axis for shading (0 x, 1 y, 2 z) and its colour. */
    static final class Hit {
        double t;
        int axis;
        int rgb;
    }

    /**
     * Nearest hit of the ray (origin o, direction d) closer than {@code max}; fills {@code out} and returns true.
     */
    boolean hit(double ox, double oy, double oz, double dx, double dy, double dz, double max, Hit out) {
        double r = Math.toRadians(yaw);
        double lx = Math.cos(r), lz = Math.sin(r);    // the figure's left
        double fx = -Math.sin(r), fz = Math.cos(r);   // forward
        double rx = ox - x, ry = oy - y, rz = oz - z;
        double[] o = {(rx * lx + rz * lz) / SCALE, ry / SCALE, (rx * fx + rz * fz) / SCALE};
        double[] d = {(dx * lx + dz * lz) / SCALE, dy / SCALE, (dx * fx + dz * fz) / SCALE};
        boolean found = false;
        for (int[] part : PARTS) {
            double t0 = 0, t1 = max;
            int axis = -1;
            boolean miss = false;
            for (int i = 0; i < 3 && !miss; i++) {
                double lo = part[i], hi = part[i + 3];
                if (Math.abs(d[i]) < 1e-9) {
                    miss = o[i] < lo || o[i] > hi;
                    continue;
                }
                double a = (lo - o[i]) / d[i], b = (hi - o[i]) / d[i];
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
                miss = t0 > t1;
            }
            if (miss || axis < 0 || t0 >= max) continue;
            max = t0;
            out.t = t0;
            out.axis = axis;
            out.rgb = texel(part, axis, d[axis] > 0, o[0] + d[0] * t0, o[1] + d[1] * t0, o[2] + d[2] * t0);
            found = true;
        }
        return found;
    }

    /** Skin colour where the ray entered the part through the face on {@code axis} (entering = moving +axis). */
    private int texel(int[] p, int axis, boolean entering, double hx, double hy, double hz) {
        int w = p[3] - p[0], h = p[4] - p[1], dd = p[5] - p[2];
        int x = clamp((int) Math.floor(hx - p[0]), w), y = clamp((int) Math.floor(hy - p[1]), h);
        int z = clamp((int) Math.floor(hz - p[2]), dd);
        int col, row;
        switch (axis) {
            case 2 -> { // front (+z) or back
                row = dd + (h - 1 - y);
                col = entering ? dd * 2 + w + (w - 1 - x) : dd + x;
            }
            case 0 -> { // right side (-x) or left side (+x)
                row = dd + (h - 1 - y);
                col = entering ? z : dd + w + (dd - 1 - z);
            }
            default -> { // top or bottom
                row = entering ? dd - 1 - z : z;
                col = entering ? dd + w + x : dd + x;
            }
        }
        int over = skin[(p[9] + row) * 64 + p[8] + col];
        int argb = (over >>> 24) > 0 ? over : skin[(p[7] + row) * 64 + p[6] + col];
        int slot = p[10] == 2 && y < 4 && armour[3] != 0 ? 3 : p[10];
        int a = armour[slot];
        if (a != 0) {
            // armour: its colour, keeping a little of the skin's shading so faces and folds still read
            float l = 0.8f + 0.2f * (((argb >> 16 & 255) + (argb >> 8 & 255) + (argb & 255)) / 765f);
            return (int) ((a >> 16 & 255) * l) << 16 | (int) ((a >> 8 & 255) * l) << 8 | (int) ((a & 255) * l);
        }
        return argb & 0xFFFFFF;
    }

    private static int clamp(int v, int size) {
        return v < 0 ? 0 : Math.min(v, size - 1);
    }
}

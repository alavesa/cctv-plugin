package fi.alavesa.cctv.monitor;

/**
 * A player-shaped figure on the monitor feed: the six boxes of the player model (head, body, arms, legs) textured
 * from the skin, outer layer included, and posed like the client animates players: legs and arms swing with the
 * walking speed, the head turns and tilts to where the player looks, sneaking leans the body, an attack swings the
 * right arm, swimming/gliding/sleeping lay the body flat, a hurt figure flashes red. Built on the main thread, read
 * by render threads (immutable).
 *
 * <p>Model space is in skin pixels from the feet: x toward the figure's LEFT hand, y up, z forward. The player model
 * is 32 px tall and drawn at 0.9375 scale (1.875 blocks), like the client. Each part turns about its joint (neck,
 * shoulders, hips); a ray is moved into the part's own space instead of moving the boxes.
 */
final class Figure {

    /** Pose and animation state, sampled on the main thread. */
    record Anim(float headYaw, float headPitch, float limbPos, float limbAmount, boolean sneaking, float swing,
                Lying lying, boolean hurt, float age) {
        static final Anim STILL = new Anim(0, 0, 0, 0, false, -1, Lying.NO, false, 0);
    }

    enum Lying { NO, PRONE, ON_BACK }

    static final double SCALE = 0.9375 / 16;

    /** x0 y0 z0 x1 y1 z1 | u v | overlay u v | armour slot (head, chest, legs) | joint x y z */
    private static final double[][] PARTS = {
            {-4, 24, -4, 4, 32, 4, 0, 0, 32, 0, 0, 0, 24, 0},       // head + hat
            {-4, 12, -2, 4, 24, 2, 16, 16, 16, 32, 1, 0, 24, 0},    // body + jacket
            {-8, 12, -2, -4, 24, 2, 40, 16, 40, 32, 1, -5, 22, 0},  // right arm + sleeve
            {4, 12, -2, 8, 24, 2, 32, 48, 48, 48, 1, 5, 22, 0},     // left arm + sleeve
            {-4, 0, -2, 0, 12, 2, 0, 16, 0, 32, 2, -1.9, 12, 0},    // right leg + trousers
            {0, 0, -2, 4, 12, 2, 16, 48, 0, 48, 2, 1.9, 12, 0},     // left leg + trousers
    };
    private static final double[] HIP = {0, 12, 0};
    private static final double SNEAK_LEAN = 0.5;

    private final double x, y, z;
    private final double lx, lz, fx, fz; // the figure's left and forward, world space
    private final int[] skin;
    private final int[] armour;
    private final boolean hurt;
    /** per part: the chain of joints from the whole figure inward, as {pivot x, y, z, 3x3 rotation...} */
    private final double[][][] chain = new double[PARTS.length][][];
    private final double radius, height;

    /**
     * @param bodyYaw body yaw, degrees (Minecraft convention: 0 faces +Z)
     * @param skin    64x64 ARGB skin ({@link Skins})
     * @param armour  ARGB of helmet, chestplate, leggings, boots; 0 = none
     */
    Figure(double x, double y, double z, float bodyYaw, int[] skin, int[] armour, Anim a) {
        this.x = x;
        this.y = y;
        this.z = z;
        double r = Math.toRadians(bodyYaw);
        lx = Math.cos(r);
        lz = Math.sin(r);
        fx = -Math.sin(r);
        fz = Math.cos(r);
        this.skin = skin;
        this.armour = armour;
        this.hurt = a.hurt();

        double[] whole = switch (a.lying()) {
            case PRONE -> joint(0, 4, 0, rotX(Math.PI / 2));
            case ON_BACK -> joint(0, 2, 0, rotX(-Math.PI / 2));
            default -> null;
        };
        double[] lean = a.sneaking() && a.lying() == Lying.NO ? joint(HIP[0], HIP[1], HIP[2], rotX(SNEAK_LEAN)) : null;
        double phase = a.limbPos() * 0.6662, amt = Math.min(1, a.limbAmount());
        double sway = Math.cos(a.age() * 0.09) * 0.05 + 0.05;
        double swingLift = a.swing() >= 0 ? -Math.sin(a.swing() * Math.PI) * 1.3 : 0;
        double[][] own = {
                mul(rotY(-Math.toRadians(a.headYaw())), rotX(Math.toRadians(a.headPitch()))),
                null,
                mul(rotX(Math.cos(phase + Math.PI) * amt + swingLift), rotZ(-sway)),
                mul(rotX(Math.cos(phase) * amt), rotZ(sway)),
                rotX(Math.cos(phase) * 1.4 * amt),
                rotX(Math.cos(phase + Math.PI) * 1.4 * amt),
        };
        for (int i = 0; i < PARTS.length; i++) {
            double[][] c = new double[3][];
            int n = 0;
            if (whole != null) c[n++] = whole;
            if (lean != null && i < 4) c[n++] = lean;
            if (own[i] != null) c[n++] = joint(PARTS[i][11], PARTS[i][12], PARTS[i][13], own[i]);
            chain[i] = java.util.Arrays.copyOf(c, n);
        }
        boolean flat = a.lying() != Lying.NO;
        radius = (flat ? 34 : 12) * SCALE;
        height = (flat ? 12 : 32) * SCALE;
    }

    double minX() {
        return x - radius;
    }

    double maxX() {
        return x + radius;
    }

    double minZ() {
        return z - radius;
    }

    double maxZ() {
        return z + radius;
    }

    double minY() {
        return y;
    }

    double maxY() {
        return y + height;
    }

    /** Result of {@link #hit}: distance, the face's axis for shading (0 x, 1 y, 2 z) and its colour. */
    static final class Hit {
        double t;
        int axis;
        int rgb;
    }

    /** Nearest hit of the ray (origin o, direction d) closer than {@code max}; fills {@code out}, returns true. */
    boolean hit(double ox, double oy, double oz, double dx, double dy, double dz, double max, Hit out) {
        double rx = ox - x, ry = oy - y, rz = oz - z;
        double[] o0 = {(rx * lx + rz * lz) / SCALE, ry / SCALE, (rx * fx + rz * fz) / SCALE};
        double[] d0 = {(dx * lx + dz * lz) / SCALE, dy / SCALE, (dx * fx + dz * fz) / SCALE};
        double[] o = new double[3], d = new double[3];
        boolean found = false;
        for (int pi = 0; pi < PARTS.length; pi++) {
            double[] part = PARTS[pi];
            System.arraycopy(o0, 0, o, 0, 3);
            System.arraycopy(d0, 0, d, 0, 3);
            for (double[] j : chain[pi]) toLocal(j, o, d);
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
    private int texel(double[] p, int axis, boolean entering, double hx, double hy, double hz) {
        int w = (int) (p[3] - p[0]), h = (int) (p[4] - p[1]), dd = (int) (p[5] - p[2]);
        int px = clamp((int) Math.floor(hx - p[0]), w), py = clamp((int) Math.floor(hy - p[1]), h);
        int pz = clamp((int) Math.floor(hz - p[2]), dd);
        int col, row;
        switch (axis) {
            case 2 -> { // front (+z) or back
                row = dd + (h - 1 - py);
                col = entering ? dd * 2 + w + (w - 1 - px) : dd + px;
            }
            case 0 -> { // right side (-x) or left side (+x)
                row = dd + (h - 1 - py);
                col = entering ? pz : dd + w + (dd - 1 - pz);
            }
            default -> { // top or bottom
                row = entering ? dd - 1 - pz : pz;
                col = entering ? dd + w + px : dd + px;
            }
        }
        int u = (int) p[6], v = (int) p[7], ou = (int) p[8], ov = (int) p[9];
        int over = skin[(ov + row) * 64 + ou + col];
        int argb = (over >>> 24) > 0 ? over : skin[(v + row) * 64 + u + col];
        int slot = p[10] == 2 && py < 4 && armour[3] != 0 ? 3 : (int) p[10];
        int a = armour[slot];
        int rgb;
        if (a != 0) {
            // armour: its colour, keeping a little of the skin's shading so faces and folds still read
            float l = 0.8f + 0.2f * (((argb >> 16 & 255) + (argb >> 8 & 255) + (argb & 255)) / 765f);
            rgb = (int) ((a >> 16 & 255) * l) << 16 | (int) ((a >> 8 & 255) * l) << 8 | (int) ((a & 255) * l);
        } else {
            rgb = argb & 0xFFFFFF;
        }
        if (hurt) { // the client's red damage flash
            int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
            rgb = Math.min(255, r / 2 + 128) << 16 | (g / 2) << 8 | (b / 2);
        }
        return rgb;
    }

    private static int clamp(int v, int size) {
        return v < 0 ? 0 : Math.min(v, size - 1);
    }

    // ---- joints: {pivot x, y, z, m00 m01 m02 m10 m11 m12 m20 m21 m22}, rotation maps part space -> parent space

    private static double[] joint(double px, double py, double pz, double[] m) {
        return new double[]{px, py, pz, m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8]};
    }

    /** parent -> part space: o = R^T (o - pivot) + pivot, d = R^T d */
    private static void toLocal(double[] j, double[] o, double[] d) {
        double ax = o[0] - j[0], ay = o[1] - j[1], az = o[2] - j[2];
        o[0] = j[3] * ax + j[6] * ay + j[9] * az + j[0];
        o[1] = j[4] * ax + j[7] * ay + j[10] * az + j[1];
        o[2] = j[5] * ax + j[8] * ay + j[11] * az + j[2];
        double bx = d[0], by = d[1], bz = d[2];
        d[0] = j[3] * bx + j[6] * by + j[9] * bz;
        d[1] = j[4] * bx + j[7] * by + j[10] * bz;
        d[2] = j[5] * bx + j[8] * by + j[11] * bz;
    }

    /** about x (pointing left): positive tips the top forward / a hanging limb's end backward / the face down */
    private static double[] rotX(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{1, 0, 0, 0, c, -s, 0, s, c};
    }

    /** about y: positive turns the front toward the figure's left */
    private static double[] rotY(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{c, 0, s, 0, 1, 0, -s, 0, c};
    }

    /** about z (forward): positive swings a hanging limb's end toward the figure's left */
    private static double[] rotZ(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{c, -s, 0, s, c, 0, 0, 0, 1};
    }

    private static double[] mul(double[] a, double[] b) {
        double[] r = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int k = 0; k < 3; k++) {
                r[i * 3 + k] = a[i * 3] * b[k] + a[i * 3 + 1] * b[3 + k] + a[i * 3 + 2] * b[6 + k];
            }
        }
        return r;
    }
}

package fi.alavesa.cctv.monitor;

/**
 * RGB to map-palette bytes with ordered (Bayer 4x4) dithering. The palette has 248 colours (62 bases x 4 shades);
 * shading in full RGB and dithering between the nearest palette colours gives smooth gradients and real tints
 * instead of four flat steps per block colour. The CRT shader's soft pass blends the dither pattern back into tone.
 */
final class Quantizer {

    /** nearest palette byte for each 5-5-5 RGB value */
    private static final byte[] LUT = new byte[32768];
    /** Bayer 4x4 thresholds centred on 0, scaled to one palette step (~1/12 of the range per channel) */
    private static final float[] BAYER = new float[16];
    private static final float DITHER = 0.085f;

    static {
        int[] ids = new int[256];
        int[] cols = new int[256];
        int n = 0;
        for (int b = 1; b < MapColors.BASE.length; b++) {
            if (MapColors.BASE[b] < 0) continue;
            for (int s = 0; s < 4; s++) {
                ids[n] = b * 4 + s;
                cols[n++] = MapColors.rgb(b * 4 + s);
            }
        }
        for (int i = 0; i < 32768; i++) {
            int r = ((i >> 10) & 31) * 255 / 31, g = ((i >> 5) & 31) * 255 / 31, bl = (i & 31) * 255 / 31;
            int best = 0;
            long bestD = Long.MAX_VALUE;
            for (int k = 0; k < n; k++) {
                int c = cols[k];
                long dr = (c >> 16 & 255) - r, dg = (c >> 8 & 255) - g, db = (c & 255) - bl;
                long d = dr * dr * 3 + dg * dg * 4 + db * db * 2; // rough perceptual weights
                if (d < bestD) {
                    bestD = d;
                    best = k;
                }
            }
            LUT[i] = (byte) ids[best];
        }
        int[] m = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};
        for (int i = 0; i < 16; i++) BAYER[i] = ((m[i] + 0.5f) / 16f - 0.5f) * DITHER;
    }

    private Quantizer() {
    }

    /** Linear-ish 0..1 RGB at pixel (x, y) to a palette byte. */
    static byte quantise(float r, float g, float b, int x, int y) {
        float d = BAYER[(y & 3) * 4 + (x & 3)];
        int ri = clamp5(r + d), gi = clamp5(g + d), bi = clamp5(b + d);
        return LUT[(ri << 10) | (gi << 5) | bi];
    }

    private static int clamp5(float v) {
        int i = (int) (v * 31f + 0.5f);
        return i < 0 ? 0 : Math.min(i, 31);
    }
}

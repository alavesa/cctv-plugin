package fi.alavesa.cctv.monitor;

/**
 * Map palette of Minecraft 26.3, generated from net.minecraft.world.level.material.MapColor in the 26.3 server jar
 * (2026-11-14). A map pixel byte is {@code baseId * 4 + shade}; shades multiply the base colour by
 * 180, 220, 255, 135 (/255) for shade 0, 1, 2, 3. The client turns bytes into colours the same way, so the RGB
 * values the CRT shader compares against (sentinel pixels) are exactly {@link #rgb(int)}.
 */
public final class MapColors {

    /** Base colours by id; -1 = unused id. Id 0 is "none" (transparent). */
    static final int[] BASE = {0x000000, 0x7FB238, 0xF7E9A3, 0xC7C7C7, 0xFF0000, 0xA0A0FF, 0xA7A7A7, 0x007C00, 0xFFFFFF, 0xA4A8B8, 0x976D4D, 0x707070, 0x4040FF, 0x8F7748, 0xFFFCF5, 0xD87F33, 0xB24CD8, 0x6699D8, 0xE5E533, 0x7FCC19, 0xF27FA5, 0x4C4C4C, 0x999999, 0x4C7F99, 0x7F3FB2, 0x334CB2, 0x664C33, 0x667F33, 0x993333, 0x191919, 0xFAEE4D, 0x5CDBD5, 0x4A80FF, 0x00D93A, 0x815631, 0x700200, 0xD1B1A1, 0x9F5224, 0x95576C, 0x706C8A, 0xBA8524, 0x677535, 0xA04D4E, 0x392923, 0x876B62, 0x575C5C, 0x7A4958, 0x4C3E5C, 0x4C3223, 0x4C522A, 0x8E3C2E, 0x251610, 0xBD3031, 0x943F61, 0x5C191D, 0x167E86, 0x3A8E8C, 0x562C3E, 0x14B485, 0x646464, 0xD8AF93, 0x7FA796, 0x000000, 0x000000};

    private static final int[] MULT = {180, 220, 255, 135};

    public static final int SHADE_DARK = 0, SHADE_MID = 1, SHADE_FULL = 2, SHADE_DARKEST = 3;

    // base ids used by the renderer and the sentinel
    public static final int SNOW = 8, WATER = 12, PALE_BLUE = 5, BLACK = 29, MAGENTA = 16, DIAMOND = 31, GOLD = 30,
            PINK = 20, RED = 28, GREEN = 27, YELLOW = 18, LIGHT_GRAY = 22, GRAY = 21, ORANGE = 15, LIME = 19;

    private MapColors() {
    }

    public static byte pixel(int baseId, int shade) {
        return (byte) (baseId * 4 + shade);
    }

    /** RGB of a map pixel byte, as the 26.3 client draws it. */
    public static int rgb(int packed) {
        int id = (packed & 0xFF) >> 2;
        int shade = packed & 3;
        if (id <= 0 || id >= BASE.length || BASE[id] < 0) return 0;
        int c = BASE[id], m = MULT[shade];
        return ((c >> 16 & 0xFF) * m / 255) << 16 | ((c >> 8 & 0xFF) * m / 255) << 8 | (c & 0xFF) * m / 255;
    }

    /** Base id whose colour equals rgb exactly, or the nearest one. */
    public static int nearestBase(int rgb) {
        int best = SNOW;
        long bestD = Long.MAX_VALUE;
        for (int id = 1; id < BASE.length; id++) {
            int c = BASE[id];
            if (c < 0) continue;
            long dr = (c >> 16 & 0xFF) - (rgb >> 16 & 0xFF), dg = (c >> 8 & 0xFF) - (rgb >> 8 & 0xFF),
                    db = (c & 0xFF) - (rgb & 0xFF);
            long d = dr * dr * 3 + dg * dg * 4 + db * db * 2;
            if (d < bestD) {
                bestD = d;
                best = id;
            }
        }
        return best;
    }
}

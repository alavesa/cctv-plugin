package fi.alavesa.cctv.monitor;

import static fi.alavesa.cctv.monitor.MapColors.BLACK;
import static fi.alavesa.cctv.monitor.MapColors.DIAMOND;
import static fi.alavesa.cctv.monitor.MapColors.GOLD;
import static fi.alavesa.cctv.monitor.MapColors.MAGENTA;
import static fi.alavesa.cctv.monitor.MapColors.PINK;
import static fi.alavesa.cctv.monitor.MapColors.SNOW;
import static fi.alavesa.cctv.monitor.MapColors.pixel;

import org.bukkit.map.MapFont;
import org.bukkit.map.MinecraftFont;

/**
 * What a monitor tile tells the CRT shader, and the static frames for non-live states.
 *
 * <p>SENTINEL (read by cctv_text_fragment.glsl): a CCTV map has these four corner pixels, all "darkest" shade:
 * (0,0) magenta, (127,0) diamond, (0,127) gold, (127,127) pink. Natural terrain essentially never produces that exact
 * four-colour arrangement, and CCTV maps never leave their fixed, invisible frames. Pixel (1,0) is the state and
 * (2,0) the style, (3,0) the panel (snow, shade 0 = flat LCD; anything else = CRT); the shader also accepts the image flipped vertically, in case the texture rows are uploaded the
 * other way round.
 */
public final class Screen {

    public static final int SIZE = 128;

    /** Screen state; each maps to one fixed pixel value the shader decodes. */
    public enum State {
        LIVE(pixel(SNOW, 2)), STATIC(pixel(SNOW, 1)), NO_SIGNAL(pixel(SNOW, 0)), OFFLINE(pixel(SNOW, 3)),
        BOOT(pixel(BLACK, 2)), OFF(pixel(BLACK, 0));

        final byte code;

        State(byte code) {
            this.code = code;
        }
    }

    /** Style codes: 0 colour, 1 green mono, 2 amber mono, 3 thermal ramp. */
    private static final byte[] STYLE = {pixel(SNOW, 2), pixel(SNOW, 1), pixel(SNOW, 0), pixel(SNOW, 3)};

    public static final byte TEXT = pixel(SNOW, 2);
    public static final byte DIM_TEXT = pixel(MapColors.LIGHT_GRAY, 2);
    public static final byte BACKGROUND = pixel(BLACK, 3);

    private Screen() {
    }

    /** Pixel (3,0) of a flat-panel screen; CRTs get {@link #CRT_PANEL}. */
    private static final byte FLAT_PANEL = pixel(SNOW, 0);
    private static final byte CRT_PANEL = pixel(SNOW, 2);

    public static void stamp(byte[] tile, State state, int style, boolean flat) {
        tile[0] = pixel(MAGENTA, 3);
        tile[SIZE - 1] = pixel(DIAMOND, 3);
        tile[(SIZE - 1) * SIZE] = pixel(GOLD, 3);
        tile[(SIZE - 1) * SIZE + SIZE - 1] = pixel(PINK, 3);
        tile[1] = state.code;
        tile[2] = STYLE[Math.clamp(style, 0, 3)];
        tile[3] = flat ? FLAT_PANEL : CRT_PANEL;
    }

    /** Fills a whole monitor image (w x h px) with a dark background and centred text lines. */
    public static void textFrame(byte[] img, int w, int h, String title, String detail) {
        java.util.Arrays.fill(img, BACKGROUND);
        int scale = w >= 256 ? 3 : 2;
        drawCentered(img, w, h, title.toUpperCase(), h / 2 - 8 * scale / 2 - 4, scale, TEXT);
        drawCentered(img, w, h, detail.toUpperCase(), h / 2 + 8 * scale / 2 + 2, 1, DIM_TEXT);
    }

    /** Draws text with the vanilla map font (MinecraftFont), centred horizontally, top at y. */
    public static void drawCentered(byte[] img, int w, int h, String text, int y, int scale, byte color) {
        MapFont font = MinecraftFont.Font;
        StringBuilder ok = new StringBuilder();
        for (char ch : text.toCharArray()) ok.append(font.getChar(ch) != null ? ch : '?');
        String t = ok.toString();
        int width = 0;
        for (char ch : t.toCharArray()) width += (font.getChar(ch).getWidth() + 1) * scale;
        int x = (w - width) / 2;
        for (char ch : t.toCharArray()) {
            MapFont.CharacterSprite sp = font.getChar(ch);
            for (int r = 0; r < sp.getHeight(); r++) {
                for (int c = 0; c < sp.getWidth(); c++) {
                    if (!sp.get(r, c)) continue;
                    for (int dy = 0; dy < scale; dy++) {
                        for (int dx = 0; dx < scale; dx++) {
                            int px = x + c * scale + dx, py = y + r * scale + dy;
                            if (px >= 0 && py >= 0 && px < w && py < h) img[py * w + px] = color;
                        }
                    }
                }
            }
            x += (sp.getWidth() + 1) * scale;
        }
    }
}

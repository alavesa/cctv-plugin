package fi.alavesa.cctv.monitor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.logging.Logger;
import org.bukkit.Material;

/**
 * How each block looks to the feed renderer: average texture colour of its top, side and bottom (block_colors.tsv,
 * measured from the vanilla textures by tools/gen_block_colors.py; only averages ship), texture contrast for the
 * grain, and how light passes through it. Blocks missing from the table use their map colour. Read-only after
 * construction, so render threads share it.
 */
final class BlockLook {

    static final byte OPAQUE = 0, AIR = 1, GLASS = 2, WATER = 3;

    private final int[] top, side, bottom;
    private final float[] contrast;
    private final byte[] kind;

    @SuppressWarnings("deprecation")
    BlockLook(BlockColors colors, Logger log) {
        Material[] all = Material.values();
        top = new int[all.length];
        side = new int[all.length];
        bottom = new int[all.length];
        contrast = new float[all.length];
        kind = new byte[all.length];
        for (Material m : all) {
            int i = m.ordinal();
            if (!m.isBlock() || m.isLegacy() || m.isAir()) {
                kind[i] = AIR;
                continue;
            }
            int base = colors.of(m);
            String n = m.name();
            if (n.contains("GLASS") || n.equals("ICE") || n.equals("BARRIER")) kind[i] = GLASS;
            else if (n.equals("WATER") || n.equals("BUBBLE_COLUMN")) kind[i] = WATER;
            else kind[i] = base == 0 ? AIR : OPAQUE;
            int rgb = base == 0 ? 0x808080 : MapColors.rgb(MapColors.pixel(base, 2));
            top[i] = side[i] = bottom[i] = rgb;
            contrast[i] = 0.06f;
        }
        int loaded = 0;
        try (InputStream in = BlockLook.class.getResourceAsStream("/block_colors.tsv")) {
            if (in != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (String line; (line = r.readLine()) != null; ) {
                    if (line.startsWith("#") || line.isBlank()) continue;
                    String[] f = line.split("\t");
                    Material m = Material.getMaterial(f[0].toUpperCase(Locale.ROOT));
                    if (m == null || f.length < 5) continue;
                    int i = m.ordinal();
                    top[i] = Integer.parseInt(f[1], 16);
                    side[i] = Integer.parseInt(f[2], 16);
                    bottom[i] = Integer.parseInt(f[3], 16);
                    contrast[i] = Float.parseFloat(f[4]);
                    if (kind[i] == AIR && m.isSolid()) kind[i] = OPAQUE; // has a texture but no map colour
                    loaded++;
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warning("block_colors.tsv could not be read (" + e + "); monitors use map colours");
        }
        log.fine("block colours: " + loaded + " from textures");
    }

    /** face: 0 x-side, 1 top, 2 z-side, 3 bottom */
    int rgb(Material m, int face) {
        int i = m.ordinal();
        return face == 1 ? top[i] : face == 3 ? bottom[i] : side[i];
    }

    float contrast(Material m) {
        return contrast[m.ordinal()];
    }

    int kind(Material m) {
        return kind[m.ordinal()];
    }
}

package fi.alavesa.cctv.monitor;

import java.util.logging.Logger;
import org.bukkit.Material;

/**
 * Material -> map base colour id, built once at startup from each block's own map colour
 * ({@code BlockData#getMapColor}), i.e. the same colour a vanilla map would show. 0 = see-through (air, glass,
 * barriers, light blocks: their map colour is NONE), so rays pass through them.
 */
public final class BlockColors {

    private final byte[] base = new byte[Material.values().length];

    @SuppressWarnings("deprecation")
    public BlockColors(Logger log) {
        int failed = 0;
        for (Material m : Material.values()) {
            if (!m.isBlock() || m.isLegacy() || m.isAir()) continue;
            try {
                int rgb = m.createBlockData().getMapColor().asRGB();
                base[m.ordinal()] = rgb == 0 ? 0 : (byte) MapColors.nearestBase(rgb);
            } catch (RuntimeException e) {
                failed++;
            }
        }
        if (failed > 0) log.fine(failed + " block materials have no map colour");
    }

    /** Base id (1..63) or 0 for see-through. Thread-safe (read-only after construction). */
    public int of(Material m) {
        return base[m.ordinal()] & 0xFF;
    }
}

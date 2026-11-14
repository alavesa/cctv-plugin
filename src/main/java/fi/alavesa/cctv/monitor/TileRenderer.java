package fi.alavesa.cctv.monitor;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

/**
 * Copies a tile buffer onto the map canvas when it changed. Called by Paper both for {@code Player.sendMap} and for
 * the vanilla item-frame map updates, so it must be cheap when nothing changed (one int compare).
 */
final class TileRenderer extends MapRenderer {

    private final byte[] buffer;
    private volatile int version;
    private int drawn = -1;

    TileRenderer(byte[] buffer) {
        super(false);
        this.buffer = buffer;
    }

    void changed() {
        version++;
    }

    @Override
    @SuppressWarnings("deprecation") // setPixel(byte) is the only allocation-free way to write palette bytes
    public void render(MapView map, MapCanvas canvas, Player player) {
        int v = version;
        if (v == drawn) return;
        drawn = v;
        for (int y = 0; y < Screen.SIZE; y++) {
            int row = y * Screen.SIZE;
            for (int x = 0; x < Screen.SIZE; x++) canvas.setPixel(x, y, buffer[row + x]);
        }
    }
}

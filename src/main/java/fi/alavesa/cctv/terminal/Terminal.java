package fi.alavesa.cctv.terminal;

import java.util.UUID;
import org.bukkit.block.BlockFace;
import org.jspecify.annotations.Nullable;

/** A security terminal: an invisible barrier block with a console model, opening the camera browser. */
public final class Terminal {

    final String id;
    final String world;
    final int x, y, z;
    final BlockFace facing;
    /** network the terminal is locked to, or null for all networks */
    @Nullable String group;
    /** clearance needed to log in (checked with the ClearanceProvider) */
    int clearance;
    @Nullable UUID display;

    Terminal(String id, String world, int x, int y, int z, BlockFace facing) {
        this.id = id;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.facing = facing;
    }

    public String id() {
        return id;
    }

    public @Nullable String group() {
        return group;
    }

    public int clearance() {
        return clearance;
    }
}

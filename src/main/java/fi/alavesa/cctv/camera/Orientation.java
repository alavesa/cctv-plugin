package fi.alavesa.cctv.camera;

import fi.alavesa.cctv.api.Mount;
import org.bukkit.block.BlockFace;
import org.joml.Quaternionf;

/**
 * Rotation maths for the display parts.
 *
 * <p>Model convention (documented for the modeller in Phase 3): both parts are authored centred on the model origin
 * (block coordinates 8,8,8). The <b>head</b> has its lens pointing toward +Z (south, Blockbench "north" face is -Z).
 * The <b>base</b> bracket is authored as a FLOOR mount, so its attaching surface is at -Y and its front faces +Z.
 *
 * <p>Minecraft yaw: 0 = +Z, 90 = -X. Pitch: positive = looking down. JOML rotateY(t) maps +Z to (sin t, 0, cos t),
 * so the lens direction for a yaw is rotateY(-yaw); rotateX(p) maps +Z to (0, -sin p, cos p), which tilts the lens
 * down for positive pitch, matching Minecraft.
 */
public final class Orientation {

    private Orientation() {
    }

    /** Lens orientation for a world yaw/pitch. */
    public static Quaternionf head(float yaw, float pitch) {
        return new Quaternionf()
                .rotateY((float) Math.toRadians(-yaw))
                .rotateX((float) Math.toRadians(pitch))
                .mul(UNDO_ITEM_FLIP);
    }

    /** Bracket orientation for a mount and the base facing. */
    public static Quaternionf base(Mount mount, float baseYaw) {
        Quaternionf q = new Quaternionf().rotateY((float) Math.toRadians(-baseYaw));
        return (switch (mount) {
            case FLOOR -> q;
            // upside down: -Y attaching surface now points up into the ceiling, front still faces baseYaw
            case CEILING -> q.rotateZ((float) Math.PI);
            // rotateX(+90) maps -Y to -Z: the attaching surface faces back into the wall
            case WALL -> q.rotateX((float) (Math.PI / 2));
        }).mul(UNDO_ITEM_FLIP);
    }

    /**
     * The item_display renderer turns every item model 180° around Y before the entity transformation is applied
     * (seen on a 26.3 client: lenses authored toward +Z pointed back at -Z). Applying this half-turn first cancels
     * it, so the model convention above holds in the world.
     */
    private static final Quaternionf UNDO_ITEM_FLIP = new Quaternionf().rotateY((float) Math.PI);

    /** Yaw that looks out of a block face (the face normal). Only meaningful for horizontal faces. */
    public static float faceYaw(BlockFace face) {
        return switch (face) {
            case SOUTH -> 0f;
            case WEST -> 90f;
            case NORTH -> 180f;
            case EAST -> -90f;
            default -> 0f;
        };
    }

    public static Mount mountFor(BlockFace clickedFace) {
        return switch (clickedFace) {
            case UP -> Mount.FLOOR;
            case DOWN -> Mount.CEILING;
            default -> Mount.WALL;
        };
    }

    /** Wraps to (-180, 180]. */
    public static float wrap(float deg) {
        float d = deg % 360f;
        if (d > 180f) d -= 360f;
        if (d <= -180f) d += 360f;
        return d;
    }

    public static float snap(float deg, float step) {
        return step <= 0 ? deg : Math.round(deg / step) * step;
    }
}

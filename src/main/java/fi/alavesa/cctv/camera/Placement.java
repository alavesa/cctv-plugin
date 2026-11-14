package fi.alavesa.cctv.camera;

import fi.alavesa.cctv.api.Mount;
import fi.alavesa.cctv.config.CctvConfig;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;

/** Turns "clicked this face of this block" into a lens position, mount and base facing. */
public final class Placement {

    public record Spec(Location eye, Mount mount, float yaw, float pitch) {
    }

    private Placement() {
    }

    /**
     * @param playerYaw used for floor and ceiling mounts: the camera looks the way the placing player looks.
     *                  Wall mounts always look straight out of the wall.
     */
    public static Spec compute(CctvConfig cfg, Block clicked, BlockFace face, float playerYaw) {
        Mount mount = Orientation.mountFor(face);
        Vector n = face.getDirection();
        // centre of the clicked block, pushed out through the face plus the configured gap
        Location eye = clicked.getLocation().add(0.5, 0.5, 0.5)
                .add(n.multiply(0.5 + cfg.placementOffset));
        float yaw = switch (mount) {
            case WALL -> Orientation.faceYaw(face);
            case FLOOR, CEILING -> Orientation.wrap(Orientation.snap(playerYaw, cfg.yawSnap));
        };
        float pitch = switch (mount) {
            case WALL -> cfg.pitchWall;
            case CEILING -> cfg.pitchCeiling;
            case FLOOR -> cfg.pitchFloor;
        };
        eye.setYaw(yaw);
        eye.setPitch(pitch);
        return new Spec(eye, mount, yaw, pitch);
    }

    /** The block the lens sits in must not be solid. */
    public static boolean fits(Block clicked, BlockFace face) {
        return !clicked.getRelative(face).isSolid();
    }
}

package fi.alavesa.cctv.camera;

import fi.alavesa.cctv.api.CameraView;
import java.util.Optional;
import org.bukkit.entity.Player;

/** Lets {@link CameraManager} expose the viewing part of the API without depending on the view package. */
public interface ViewBridge {

    boolean start(Player player, Camera camera);

    /** @param controls the viewer may pan/tilt, zoom and switch (a security terminal) */
    boolean start(Player player, Camera camera, boolean controls);

    boolean stop(Player player);

    Optional<CameraView> viewing(Player player);
}

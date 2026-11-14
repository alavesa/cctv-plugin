package fi.alavesa.cctv.api.event;

import fi.alavesa.cctv.api.CameraView;
import org.bukkit.event.Event;

/** Base class for every CCTV camera event. Always fired on the main thread. */
public abstract class CameraEvent extends Event {

    private final CameraView camera;

    protected CameraEvent(CameraView camera) {
        this.camera = camera;
    }

    public CameraView getCamera() {
        return camera;
    }
}

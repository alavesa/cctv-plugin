package fi.alavesa.cctv.api;

import org.bukkit.Location;

/** Read-only view of a camera. Values are live; call again rather than caching. Main thread only. */
public interface CameraView {

    String id();

    String name();

    String group();

    CameraType type();

    /** Lens (eye) position with yaw/pitch set to the current view direction. A fresh copy each call. */
    Location location();

    String worldName();

    Mount mount();

    /** Base facing set at placement. */
    float baseYaw();

    float basePitch();

    /** Current pan/tilt offset from the base facing. */
    float pan();

    float tilt();

    /** Current view direction, baseYaw + pan and basePitch + tilt (pitch clamped to -90..90). */
    float yaw();

    float pitch();

    float fov();

    float panMin();

    float panMax();

    float tiltMin();

    float tiltMax();

    double health();

    double maxHealth();

    boolean powered();

    boolean enabled();

    int clearance();

    CameraState state();
}

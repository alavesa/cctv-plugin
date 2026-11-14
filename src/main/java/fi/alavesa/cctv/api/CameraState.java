package fi.alavesa.cctv.api;

/**
 * Derived camera state, in priority order: an admin-disabled camera reports DISABLED even if it is also broken.
 * Only ONLINE and DAMAGED cameras can be viewed.
 */
public enum CameraState {
    DISABLED,
    DESTROYED,
    UNPOWERED,
    DAMAGED,
    ONLINE;

    public boolean viewable() {
        return this == ONLINE || this == DAMAGED;
    }
}

package fi.alavesa.cctv.api.event;

import fi.alavesa.cctv.api.CameraView;
import org.bukkit.event.HandlerList;

/**
 * Fired when a camera stops being viewable (ONLINE/DAMAGED to DESTROYED/UNPOWERED/DISABLED).
 * Informational: change the cause instead (e.g. cancel the CameraDamageEvent) to prevent it.
 */
public final class CameraDisableEvent extends CameraEvent {

    public enum Reason { DESTROYED, POWER, ADMIN, REMOVED }

    private static final HandlerList HANDLERS = new HandlerList();
    private final Reason reason;

    public CameraDisableEvent(CameraView camera, Reason reason) {
        super(camera);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

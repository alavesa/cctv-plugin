package fi.alavesa.cctv.api.event;

import fi.alavesa.cctv.api.CameraView;
import org.bukkit.event.HandlerList;

/** Fired when a camera becomes viewable again (after a repair, power restore or admin enable). */
public final class CameraEnableEvent extends CameraEvent {

    public enum Reason { REPAIRED, POWER, ADMIN }

    private static final HandlerList HANDLERS = new HandlerList();
    private final Reason reason;

    public CameraEnableEvent(CameraView camera, Reason reason) {
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

package fi.alavesa.cctv.api.event;

import fi.alavesa.cctv.api.CameraView;
import org.bukkit.event.HandlerList;

/** Fired after a camera has been removed from the registry (its entities are already gone). */
public final class CameraRemoveEvent extends CameraEvent {

    public enum Cause { COMMAND, PICKUP, API }

    private static final HandlerList HANDLERS = new HandlerList();
    private final Cause cause;

    public CameraRemoveEvent(CameraView camera, Cause cause) {
        super(camera);
        this.cause = cause;
    }

    public Cause getCause() {
        return cause;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

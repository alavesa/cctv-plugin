package fi.alavesa.cctv.api.event;

import fi.alavesa.cctv.api.CameraView;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;

/** Fired before a new camera is registered. Cancel to prevent the camera from being created. */
public final class CameraCreateEvent extends CameraEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final @Nullable Player creator;
    private boolean cancelled;

    public CameraCreateEvent(CameraView camera, @Nullable Player creator) {
        super(camera);
        this.creator = creator;
    }

    /** The placing player, or null for API/console creation. */
    public @Nullable Player getCreator() {
        return creator;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

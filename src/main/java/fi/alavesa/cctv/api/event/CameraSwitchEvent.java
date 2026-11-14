package fi.alavesa.cctv.api.event;

import fi.alavesa.cctv.api.CameraView;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;

/** Fired before a viewing player moves from one camera to another. {@link #getCamera()} is the new camera. */
public final class CameraSwitchEvent extends CameraEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final CameraView from;
    private boolean cancelled;

    public CameraSwitchEvent(Player player, CameraView from, CameraView to) {
        super(to);
        this.player = player;
        this.from = from;
    }

    public Player getPlayer() {
        return player;
    }

    public CameraView getFrom() {
        return from;
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

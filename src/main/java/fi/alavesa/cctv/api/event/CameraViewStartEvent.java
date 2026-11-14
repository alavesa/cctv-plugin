package fi.alavesa.cctv.api.event;

import fi.alavesa.cctv.api.CameraView;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;

/**
 * Fired before a player's view is attached to a camera. Cancel to refuse (for example while the player is in combat
 * or restrained). Access and camera state have already been checked.
 */
public final class CameraViewStartEvent extends CameraEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private boolean cancelled;

    public CameraViewStartEvent(Player player, CameraView camera) {
        super(camera);
        this.player = player;
    }

    public Player getPlayer() {
        return player;
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

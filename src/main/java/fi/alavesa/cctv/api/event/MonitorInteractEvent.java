package fi.alavesa.cctv.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/** A player clicked a monitor screen and is about to open the camera it shows. Cancel to keep them out. */
public final class MonitorInteractEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final String monitorId;
    private final String cameraId;
    private boolean cancelled;

    public MonitorInteractEvent(Player player, String monitorId, String cameraId) {
        super(player);
        this.monitorId = monitorId;
        this.cameraId = cameraId;
    }

    public String getMonitorId() {
        return monitorId;
    }

    public String getCameraId() {
        return cameraId;
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

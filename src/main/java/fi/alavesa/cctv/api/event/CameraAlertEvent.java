package fi.alavesa.cctv.api.event;

import fi.alavesa.cctv.api.CameraView;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;

/**
 * A camera saw a player inside a restricted zone of its network. Cancel to ignore the sighting (for example for
 * staff on duty, or a player your SCP framework says is escorted). Fired at most once per camera, zone and player
 * per alert cooldown.
 */
public final class CameraAlertEvent extends CameraEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player intruder;
    private final String zone;
    private boolean cancelled;

    public CameraAlertEvent(CameraView camera, Player intruder, String zone) {
        super(camera);
        this.intruder = intruder;
        this.zone = zone;
    }

    public Player getIntruder() {
        return intruder;
    }

    public String getZone() {
        return zone;
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

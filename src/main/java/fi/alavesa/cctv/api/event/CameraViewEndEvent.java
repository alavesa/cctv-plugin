package fi.alavesa.cctv.api.event;

import fi.alavesa.cctv.api.CameraView;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/** Fired after a viewing session ended and the player was restored (game mode, position, effects). */
public final class CameraViewEndEvent extends CameraEvent {

    public enum Reason {
        /** The player pressed the exit key. */
        EXIT,
        /** The body double was hurt: the player was pulled out ("SIGNAL LOST"). */
        DAMAGED,
        /** The camera went offline (destroyed, unpowered, disabled, removed) and there was nothing to switch to. */
        CAMERA_LOST,
        QUIT,
        DEATH,
        /** Another plugin teleported the player. */
        TELEPORT,
        /** Another plugin changed the player's game mode. */
        GAMEMODE,
        /** API or command. */
        API,
        /** Plugin disable or server shutdown. */
        SHUTDOWN
    }

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final Reason reason;

    public CameraViewEndEvent(Player player, CameraView camera, Reason reason) {
        super(camera);
        this.player = player;
        this.reason = reason;
    }

    public Player getPlayer() {
        return player;
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

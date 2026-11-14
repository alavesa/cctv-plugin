package fi.alavesa.cctv.api.event;

import fi.alavesa.cctv.api.CameraView;
import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;

/** Fired before a camera loses health. The amount can be changed; cancel to ignore the hit. */
public final class CameraDamageEvent extends CameraEvent implements Cancellable {

    public enum Cause { MELEE, PROJECTILE, EXPLOSION, API }

    private static final HandlerList HANDLERS = new HandlerList();
    private final @Nullable Entity source;
    private final Cause cause;
    private double amount;
    private boolean cancelled;

    public CameraDamageEvent(CameraView camera, @Nullable Entity source, Cause cause, double amount) {
        super(camera);
        this.source = source;
        this.cause = cause;
        this.amount = amount;
    }

    /** The attacker (player, projectile shooter, TNT...) if known. */
    public @Nullable Entity getSource() {
        return source;
    }

    public Cause getCause() {
        return cause;
    }

    public double getAmount() {
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = Math.max(0, amount);
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

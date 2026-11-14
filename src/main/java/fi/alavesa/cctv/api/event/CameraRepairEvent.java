package fi.alavesa.cctv.api.event;

import fi.alavesa.cctv.api.CameraView;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;

/** Fired before a camera regains health. The amount can be changed; cancel to refuse the repair. */
public final class CameraRepairEvent extends CameraEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final @Nullable Player repairer;
    private double amount;
    private boolean cancelled;

    public CameraRepairEvent(CameraView camera, @Nullable Player repairer, double amount) {
        super(camera);
        this.repairer = repairer;
        this.amount = amount;
    }

    public @Nullable Player getRepairer() {
        return repairer;
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

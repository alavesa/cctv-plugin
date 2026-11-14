package fi.alavesa.cctv.monitor;

import fi.alavesa.cctv.config.Messages;
import java.util.function.Supplier;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** World-facing monitor behaviour: placement, clicks, protection of the frames, chunk lifecycle, cache upkeep. */
public final class MonitorListener implements Listener {

    private final MonitorManager monitors;
    private final Supplier<Messages> messages;

    public MonitorListener(MonitorManager monitors, Supplier<Messages> messages) {
        this.monitors = monitors;
        this.messages = messages;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        monitors.onChunkLoad(e.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent e) {
        monitors.onChunkUnload(e.getChunk());
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity entity : e.getEntities()) {
            if (monitors.isTagged(entity) && !monitors.isLive(entity)) entity.remove();
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlace(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = e.getItem();
        String type = monitors.placerType(item);
        if (type == null || item == null || e.getClickedBlock() == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!p.hasPermission("cctv.monitor.place")) {
            messages.get().send(p, "no-permission");
            return;
        }
        MonitorManager.Placed r = monitors.place(type, e.getClickedBlock(), e.getBlockFace(), p);
        if (r.result() == MonitorManager.PlaceResult.OK && r.monitor() != null) {
            if (p.getGameMode() != GameMode.CREATIVE) item.subtract();
            messages.get().send(p, "monitor-placed", Messages.p("id", r.monitor().id()));
        } else {
            messages.get().send(p, "monitor-place-" + r.result().name().toLowerCase().replace('_', '-'));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEntityEvent e) {
        Monitor m = monitors.of(e.getRightClicked());
        if (m == null) return;
        e.setCancelled(true); // no rotating or taking the map
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!e.getPlayer().hasPermission("cctv.monitor.use")) {
            messages.get().send(e.getPlayer(), "no-permission");
            return;
        }
        if (!m.powered()) {
            messages.get().send(e.getPlayer(), "monitor-off", Messages.p("id", m.id()));
            return;
        }
        if (m.cameras().isEmpty()) {
            messages.get().send(e.getPlayer(), "monitor-unbound", Messages.p("id", m.id()));
            return;
        }
        monitors.interact(e.getPlayer(), m);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHit(EntityDamageByEntityEvent e) {
        Monitor m = monitors.of(e.getEntity());
        if (m == null) return;
        e.setCancelled(true);
        if (e.getDamager() instanceof Player p && p.isSneaking() && p.hasPermission("cctv.monitor.pickup")) {
            pickup(p, m);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        if (monitors.of(e.getEntity()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHangingBreak(HangingBreakEvent e) {
        // frames hang on barriers / walls; never let physics or explosions pop them
        if (monitors.of(e.getEntity()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreakBack(BlockBreakEvent e) {
        Monitor m = monitors.atBackBlock(e.getBlock());
        if (m == null) {
            monitors.invalidate(e.getBlock());
            return;
        }
        // breaking the wall (or a desk monitor's body) takes the monitor down with it
        e.setCancelled(true);
        if (e.getPlayer().hasPermission("cctv.monitor.pickup")) pickup(e.getPlayer(), m);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaceBlock(BlockPlaceEvent e) {
        monitors.invalidate(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> monitors.atBackBlock(b) != null);
        for (Block b : e.blockList()) monitors.invalidate(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> monitors.atBackBlock(b) != null);
        for (Block b : e.blockList()) monitors.invalidate(b);
    }

    private void pickup(Player p, Monitor m) {
        MonitorConfig.Type type = m.type();
        monitors.remove(m.id());
        if (p.getGameMode() != GameMode.CREATIVE) {
            p.getInventory().addItem(monitors.placer(type, 1))
                    .values().forEach(left -> p.getWorld().dropItem(p.getLocation(), left));
        }
        messages.get().send(p, "monitor-removed", Messages.p("id", m.id()));
    }
}

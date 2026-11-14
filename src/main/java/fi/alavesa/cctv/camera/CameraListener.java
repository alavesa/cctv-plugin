package fi.alavesa.cctv.camera;

import fi.alavesa.cctv.api.CameraType;
import fi.alavesa.cctv.api.event.CameraDamageEvent;
import fi.alavesa.cctv.api.event.CameraRemoveEvent;
import fi.alavesa.cctv.config.Messages;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.util.function.Supplier;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;

/** World-facing camera behaviour: chunk lifecycle, placement, hits, repairs. */
public final class CameraListener implements Listener {

    private final CameraManager cameras;
    private final Supplier<Messages> messages;

    public CameraListener(CameraManager cameras, Supplier<Messages> messages) {
        this.cameras = cameras;
        this.messages = messages;
    }

    // ------------------------------------------------------------------ chunks

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        cameras.onChunkLoad(e.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent e) {
        cameras.onChunkUnload(e.getChunk());
    }

    /** Removes CCTV-tagged entities that aren't live parts (written by an older build, or left by a crash). */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity entity : e.getEntities()) {
            if (cameras.isTagged(entity) && !cameras.isLivePart(entity)) entity.remove();
        }
    }

    // ------------------------------------------------------------------ placement

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlace(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = e.getItem();
        String typeKey = cameras.items().placerType(item);
        if (typeKey == null || item == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Block block = e.getClickedBlock();
        if (block == null) return;
        if (!p.hasPermission("cctv.place")) {
            messages.get().send(p, "no-permission");
            return;
        }
        CameraType type = cameras.types().get(typeKey);
        if (type == null) {
            messages.get().send(p, "unknown-type", Messages.p("type", typeKey));
            return;
        }
        if (!Placement.fits(block, e.getBlockFace())) {
            messages.get().send(p, "place-blocked");
            return;
        }
        Placement.Spec spec = Placement.compute(cameras.config(), block, e.getBlockFace(), p.getYaw());
        if (cameras.nearest(spec.eye(), cameras.config().minSpacing) != null) {
            messages.get().send(p, "place-too-close");
            return;
        }
        cameras.create(type.key(), spec.eye(), spec.mount(), spec.yaw(), spec.pitch(), null,
                cameras.items().placerGroup(item), p).ifPresent(c -> {
            if (p.getGameMode() != GameMode.CREATIVE) item.subtract();
            messages.get().send(p, "placed", Messages.p("id", c.id()), Messages.p("group", c.group()));
        });
    }

    // ------------------------------------------------------------------ hits

    /** Fires for every attack attempt, including entities that can't take damage (interaction boxes). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(PrePlayerAttackEntityEvent e) {
        Camera c = cameras.cameraOf(e.getAttacked());
        if (c == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (p.isSneaking() && p.hasPermission("cctv.pickup")) {
            cameras.remove(c.id(), CameraRemoveEvent.Cause.PICKUP);
            if (p.getGameMode() != GameMode.CREATIVE) {
                CameraType type = cameras.types().getOrDefault(c.typeKey(), c.type());
                p.getInventory().addItem(cameras.items().placer(type, 1, c.group()))
                        .values().forEach(left -> p.getWorld().dropItem(p.getLocation(), left));
            }
            messages.get().send(p, "picked-up", Messages.p("id", c.id()));
            return;
        }
        if (!cameras.config().playersCanDamage || !p.hasPermission("cctv.damage")) return;
        cameras.damage(c, cameras.config().meleeDamage, p, CameraDamageEvent.Cause.MELEE);
    }

    @EventHandler(ignoreCancelled = true)
    public void onProjectile(ProjectileHitEvent e) {
        Entity hit = e.getHitEntity();
        if (hit == null) return;
        Camera c = cameras.cameraOf(hit);
        if (c == null) return;
        Projectile proj = e.getEntity();
        ProjectileSource shooter = proj.getShooter();
        Entity source = shooter instanceof Entity ent ? ent : proj;
        if (source instanceof Player p && (!cameras.config().playersCanDamage || !p.hasPermission("cctv.damage"))) {
            return;
        }
        cameras.damage(c, cameras.config().projectileDamage, source, CameraDamageEvent.Cause.PROJECTILE);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        cameras.explosion(e.getLocation(), e.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        cameras.explosion(e.getBlock().getLocation().add(0.5, 0.5, 0.5), null);
    }

    // ------------------------------------------------------------------ right-click

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent e) {
        Camera c = cameras.cameraOf(e.getRightClicked());
        if (c == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        ItemStack held = p.getInventory().getItemInMainHand();
        if (cameras.items().isRepairItem(held) && p.hasPermission("cctv.repair")) {
            if (c.health() >= c.maxHealth()) {
                messages.get().send(p, "repair-full", Messages.p("id", c.id()));
                return;
            }
            double after = cameras.repair(c, cameras.config().repairAmount, p);
            if (after >= 0 && cameras.config().repairConsumes && p.getGameMode() != GameMode.CREATIVE) {
                held.subtract();
            }
            if (after >= 0) {
                messages.get().send(p, "repaired", Messages.p("id", c.id()),
                        Messages.p("health", fmt(after)), Messages.p("max", fmt(c.maxHealth())));
            }
            return;
        }
        if (p.hasPermission("cctv.command.info")) {
            messages.get().send(p, "hit-info", Messages.p("id", c.id()), Messages.p("name", c.name()),
                    Messages.p("state", c.state().name()), Messages.p("health", fmt(c.health())),
                    Messages.p("max", fmt(c.maxHealth())));
        }
    }

    static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}

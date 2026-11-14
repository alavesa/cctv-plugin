package fi.alavesa.cctv.camera;

import fi.alavesa.cctv.api.CameraState;
import fi.alavesa.cctv.config.CctvConfig;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Spawns and updates the entities that make a camera visible and hittable:
 * <ul>
 *   <li>base: item_display with the bracket model (optional, fixed rotation from the mount)</li>
 *   <li>head: item_display with the lens model, rotated to the current pan/tilt</li>
 *   <li>hit: interaction box around the lens for clicks and hits</li>
 * </ul>
 * All parts are non-persistent: they are never written to the world, so a crash cannot leave duplicates behind.
 * The camera record re-spawns them on the next chunk load.
 */
final class CameraParts {

    private final Keys keys;
    private CctvConfig cfg;

    CameraParts(Keys keys, CctvConfig cfg) {
        this.keys = keys;
        this.cfg = cfg;
    }

    void config(CctvConfig cfg) {
        this.cfg = cfg;
    }

    /** Spawns the parts if the camera's chunk is loaded and they don't exist yet. */
    void spawn(Camera c) {
        if (c.spawned()) return;
        World w = c.bukkitWorld();
        if (w == null || !w.isChunkLoaded(c.chunkX(), c.chunkZ())) return;
        // yaw/pitch 0: the display's own rotation stays neutral, everything is in the transformation
        Location at = new Location(w, c.x, c.y, c.z, 0f, 0f);
        float s = c.type.modelScale();
        NamespacedKey baseModel = c.type.baseModel();
        if (baseModel != null) {
            c.baseEntity = w.spawn(at, ItemDisplay.class, d -> {
                common(d, c, "base");
                d.setItemStack(modelItem(baseModel));
                d.setTransformation(transform(Orientation.base(c.mount, c.baseYaw), s));
            }).getUniqueId();
        }
        c.headEntity = w.spawn(at, ItemDisplay.class, d -> {
            common(d, c, "head");
            d.setItemStack(modelItem(headModel(c)));
            d.setTransformation(transform(Orientation.head(c.yaw(), c.pitch()), s));
        }).getUniqueId();
        // an interaction box grows up from its location, so drop it by half its height to centre it on the lens
        Location hitAt = at.clone().subtract(0, cfg.hitboxHeight / 2.0, 0);
        c.hitEntity = w.spawn(hitAt, Interaction.class, i -> {
            i.setPersistent(false);
            i.setInteractionWidth(cfg.hitboxWidth);
            i.setInteractionHeight(cfg.hitboxHeight);
            i.setResponsive(true);
            tag(i, c, "hit");
        }).getUniqueId();
    }

    void despawn(Camera c) {
        remove(c.baseEntity);
        remove(c.headEntity);
        remove(c.hitEntity);
        c.baseEntity = null;
        c.headEntity = null;
        c.hitEntity = null;
    }

    /** Re-applies the head rotation; the client interpolates over {@code ticks}. */
    void updateHead(Camera c, int ticks) {
        if (!(entity(c.headEntity) instanceof ItemDisplay d)) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(transform(Orientation.head(c.yaw(), c.pitch()), c.type.modelScale()));
    }

    /** Swaps the head between its normal and broken model (called when the DESTROYED state changes). */
    void refreshModel(Camera c) {
        if (entity(c.headEntity) instanceof ItemDisplay d) d.setItemStack(modelItem(headModel(c)));
    }

    /** The live model entities (base, head); used to hide a camera's own housing from its viewers. */
    java.util.List<Entity> models(Camera c) {
        java.util.List<Entity> out = new java.util.ArrayList<>(2);
        Entity b = entity(c.baseEntity), h = entity(c.headEntity);
        if (b != null) out.add(b);
        if (h != null) out.add(h);
        return out;
    }

    boolean owns(UUID entity, Camera c) {
        return entity.equals(c.baseEntity) || entity.equals(c.headEntity) || entity.equals(c.hitEntity);
    }

    /** Camera id stored on a part entity, or null if the entity isn't ours. */
    @Nullable String cameraIdOf(Entity e) {
        return e.getPersistentDataContainer().get(keys.camera, PersistentDataType.STRING);
    }

    ItemStack modelItem(NamespacedKey model) {
        ItemStack item = new ItemStack(cfg.itemMaterial);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(model);
        item.setItemMeta(meta);
        return item;
    }

    private NamespacedKey headModel(Camera c) {
        return c.state() == CameraState.DESTROYED ? c.type.brokenModel() : c.type.headModel();
    }

    private void common(ItemDisplay d, Camera c, String part) {
        d.setPersistent(false);
        // NONE: model coordinates map 1:1 to the display, no item-frame/hand transforms applied
        d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
        d.setBillboard(Display.Billboard.FIXED);
        d.setInterpolationDuration(cfg.ptzUpdateTicks);
        tag(d, c, part);
    }

    private void tag(Entity e, Camera c, String part) {
        e.getPersistentDataContainer().set(keys.camera, PersistentDataType.STRING, c.id);
        e.getPersistentDataContainer().set(keys.part, PersistentDataType.STRING, part);
    }

    private static Transformation transform(Quaternionf rotation, float scale) {
        return new Transformation(new Vector3f(), rotation, new Vector3f(scale, scale, scale), new Quaternionf());
    }

    private static @Nullable Entity entity(@Nullable UUID id) {
        return id == null ? null : Bukkit.getEntity(id);
    }

    private static void remove(@Nullable UUID id) {
        Entity e = entity(id);
        if (e != null) e.remove();
    }
}

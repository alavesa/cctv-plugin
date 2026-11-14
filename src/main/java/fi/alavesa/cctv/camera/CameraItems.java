package fi.alavesa.cctv.camera;

import fi.alavesa.cctv.api.CameraType;
import fi.alavesa.cctv.config.CctvConfig;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

/** The camera placement item: any item material carrying an item_model plus a PDC tag with the type key. */
public final class CameraItems {

    private final Keys keys;
    private CctvConfig cfg;

    CameraItems(Keys keys, CctvConfig cfg) {
        this.keys = keys;
        this.cfg = cfg;
    }

    void config(CctvConfig cfg) {
        this.cfg = cfg;
    }

    public ItemStack placer(CameraType type, int amount, String group) {
        ItemStack item = new ItemStack(cfg.itemMaterial, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(type.placerModel());
        meta.itemName(type.displayName());
        meta.lore(List.of(
                Component.text("Group: " + group, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Right-click a block face to mount", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(keys.placer, PersistentDataType.STRING, type.key());
        pdc.set(keys.placerGroup, PersistentDataType.STRING, group);
        item.setItemMeta(meta);
        return item;
    }

    /** Type key of a placement item, or null. */
    public @Nullable String placerType(@Nullable ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(keys.placer, PersistentDataType.STRING);
    }

    public String placerGroup(ItemStack item) {
        String g = item.getItemMeta().getPersistentDataContainer().get(keys.placerGroup, PersistentDataType.STRING);
        return g == null ? "default" : g;
    }

    /** Whether an item is the configured repair item (material, plus item_model if one is configured). */
    public boolean isRepairItem(@Nullable ItemStack item) {
        if (item == null || item.getType() != cfg.repairMaterial) return false;
        if (cfg.repairModel == null) return true;
        return item.hasItemMeta() && cfg.repairModel.equals(item.getItemMeta().getItemModel());
    }
}

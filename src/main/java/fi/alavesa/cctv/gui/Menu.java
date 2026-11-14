package fi.alavesa.cctv.gui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jspecify.annotations.Nullable;

/**
 * A 6-row chest menu with per-slot click handlers. Every click inside it is cancelled (by {@link MenuListener}), so
 * items can never be taken out; handlers only run for clicks in the menu's own slots.
 */
public abstract class Menu implements InventoryHolder {

    protected final Player viewer;
    private final Map<Integer, Consumer<InventoryClickEvent>> handlers = new HashMap<>();
    private @Nullable Inventory inventory;

    protected Menu(Player viewer) {
        this.viewer = viewer;
    }

    protected abstract Component title();

    /** Fills the slots; called on open and on {@link #refresh()}. */
    protected abstract void render();

    public void open() {
        inventory = Bukkit.createInventory(this, 54, title());
        render();
        viewer.openInventory(inventory);
    }

    /** Re-renders in place (same window, no flicker). */
    public void refresh() {
        if (inventory == null) return;
        inventory.clear();
        handlers.clear();
        render();
    }

    protected void set(int slot, ItemStack item, @Nullable Consumer<InventoryClickEvent> onClick) {
        if (inventory == null) return;
        inventory.setItem(slot, item);
        if (onClick != null) handlers.put(slot, onClick);
        else handlers.remove(slot);
    }

    void click(InventoryClickEvent e) {
        Consumer<InventoryClickEvent> h = handlers.get(e.getRawSlot());
        if (h != null) {
            viewer.playSound(viewer.getLocation(), "minecraft:ui.button.click", 0.4f, 1.4f);
            h.accept(e);
        }
    }

    @Override
    public Inventory getInventory() {
        if (inventory == null) throw new IllegalStateException("menu not open");
        return inventory;
    }

    // ------------------------------------------------------------------ item helpers

    protected static ItemStack icon(Material material, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(material);
        ItemMeta m = it.getItemMeta();
        m.itemName(name);
        m.lore(lore.stream().map(l -> l.decoration(TextDecoration.ITALIC, false)).toList());
        m.addItemFlags(ItemFlag.values());
        it.setItemMeta(m);
        return it;
    }

    protected static ItemStack icon(NamespacedKey model, Component name, List<Component> lore) {
        ItemStack it = icon(Material.PAPER, name, lore);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(model);
        it.setItemMeta(m);
        return it;
    }

    protected static Component line(String text, NamedTextColor color) {
        return Component.text(text, color);
    }
}

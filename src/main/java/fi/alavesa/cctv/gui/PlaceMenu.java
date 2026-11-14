package fi.alavesa.cctv.gui;

import fi.alavesa.cctv.api.CameraType;
import fi.alavesa.cctv.monitor.MonitorConfig;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Hands out placement items: every camera type, every monitor type and the security terminal. */
public final class PlaceMenu extends Menu {

    private final GuiContext ctx;
    private final Menu back;

    public PlaceMenu(GuiContext ctx, Player viewer, Menu back) {
        super(viewer);
        this.ctx = ctx;
        this.back = back;
    }

    @Override
    protected Component title() {
        return GuiTitle.of("PLACE", ctx.packLoaded().test(viewer));
    }

    @Override
    protected void render() {
        int slot = 10;
        for (CameraType t : ctx.cameras().types().values()) {
            if (slot > 16) break;
            give(slot++, ctx.cameras().items().placer(t, 1, "default"));
        }
        slot = 19;
        for (MonitorConfig.Type t : ctx.monitors().config().types.values()) {
            if (slot > 25) break;
            give(slot++, ctx.monitors().placer(t, 1));
        }
        give(28, ctx.terminalPlacer().get());
        set(45, icon(Material.ARROW, line("Back", NamedTextColor.WHITE), List.of()), e -> back.open());
        set(49, icon(Material.PAPER, line("Click: one, shift-click: 16", NamedTextColor.GRAY), List.of()), null);
    }

    private void give(int slot, ItemStack item) {
        set(slot, item.clone(), e -> {
            ItemStack g = item.clone();
            g.setAmount(e.isShiftClick() ? 16 : 1);
            viewer.getInventory().addItem(g).values()
                    .forEach(left -> viewer.getWorld().dropItem(viewer.getLocation(), left));
        });
    }
}

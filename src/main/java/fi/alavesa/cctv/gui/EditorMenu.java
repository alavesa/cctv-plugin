package fi.alavesa.cctv.gui;

import fi.alavesa.cctv.api.CameraType;
import fi.alavesa.cctv.api.event.CameraRemoveEvent;
import fi.alavesa.cctv.camera.Camera;
import fi.alavesa.cctv.camera.CameraManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;

/** Admin editor for one camera: everything /cctv set|rename|setgroup|power|... does, by clicking. */
public final class EditorMenu extends Menu {

    private final GuiContext ctx;
    private final String cameraId;
    private final Menu back;

    public EditorMenu(GuiContext ctx, Player viewer, String cameraId, Menu back) {
        super(viewer);
        this.ctx = ctx;
        this.cameraId = cameraId;
        this.back = back;
    }

    @Override
    protected Component title() {
        return GuiTitle.of("EDIT " + cameraId.toUpperCase(Locale.ROOT), ctx.packLoaded().test(viewer));
    }

    @Override
    protected void render() {
        CameraManager cm = ctx.cameras();
        Camera c = cm.get(cameraId);
        if (c == null) {
            back.open();
            return;
        }
        set(4, icon(Material.OBSERVER, line(c.name(), NamedTextColor.WHITE), List.of(
                line(c.id() + "  [" + c.group() + "]  " + c.type().key() + "  " + c.state(), NamedTextColor.GRAY),
                line("Health " + Math.round(c.health()) + "/" + Math.round(c.maxHealth()) + "  clearance "
                        + c.clearance(), NamedTextColor.GRAY),
                line("Facing " + Math.round(c.baseYaw()) + "/" + Math.round(c.basePitch()) + "  fov "
                        + Math.round(c.fov()), NamedTextColor.GRAY))), null);

        set(19, button(Material.NAME_TAG, "Rename", c.name(), "Click: type a new name"), e -> prompt("Name", c.name(),
                v -> cm.rename(cameraId, v)));
        set(20, button(Material.BOOK, "Network", c.group(), "Click: type a network"), e -> prompt("Network", c.group(),
                v -> cm.setGroup(cameraId, v)));
        set(21, button(Material.OBSERVER, "Type", c.type().key(), "Click: next type, right-click: previous"),
                e -> cycleType(c, e.getClick() == ClickType.RIGHT ? -1 : 1));
        set(22, button(c.powered() ? Material.REDSTONE_TORCH : Material.LEVER, "Power", c.powered() ? "on" : "off",
                "Click: toggle"), e -> {
            cm.setPowered(cameraId, !c.powered());
            refresh();
        });
        set(23, button(c.enabled() ? Material.LIME_DYE : Material.GRAY_DYE, "Enabled", String.valueOf(c.enabled()),
                "Click: toggle"), e -> {
            cm.setEnabled(cameraId, !c.enabled());
            refresh();
        });
        set(24, button(Material.IRON_INGOT, "Repair", Math.round(c.health()) + "/" + Math.round(c.maxHealth()),
                "Click: full repair"), e -> {
            cm.repair(c, c.maxHealth(), viewer);
            refresh();
        });
        set(25, button(Material.ENDER_PEARL, "Teleport", c.worldName(), "Click: go to the camera"), e -> {
            viewer.closeInventory();
            viewer.performCommand("cctv tp " + cameraId);
        });

        set(28, step(Material.SPYGLASS, "Field of view", c.fov(), 5), e -> {
            cm.setFov(c, c.fov() + delta(e, 5));
            refresh();
        });
        set(29, step(Material.TRIPWIRE_HOOK, "Clearance", c.clearance(), 1), e -> {
            cm.setClearance(c, c.clearance() + (int) delta(e, 1));
            refresh();
        });
        set(30, step(Material.COMPASS, "Facing yaw", c.baseYaw(), 15), e -> {
            cm.setBase(c, c.baseYaw() + delta(e, 15), c.basePitch());
            refresh();
        });
        set(31, step(Material.CLOCK, "Facing pitch", c.basePitch(), 5), e -> {
            cm.setBase(c, c.baseYaw(), c.basePitch() + delta(e, 5));
            refresh();
        });
        set(32, step(Material.STICK, "Pan left limit", c.panMin(), 5), e -> {
            cm.setLimits(c, c.panMin() + delta(e, 5), c.panMax(), c.tiltMin(), c.tiltMax());
            refresh();
        });
        set(33, step(Material.STICK, "Pan right limit", c.panMax(), 5), e -> {
            cm.setLimits(c, c.panMin(), c.panMax() + delta(e, 5), c.tiltMin(), c.tiltMax());
            refresh();
        });
        set(34, step(Material.BLAZE_ROD, "Tilt up limit", c.tiltMin(), 5), e -> {
            cm.setLimits(c, c.panMin(), c.panMax(), c.tiltMin() + delta(e, 5), c.tiltMax());
            refresh();
        });
        set(35, step(Material.BLAZE_ROD, "Tilt down limit", c.tiltMax(), 5), e -> {
            cm.setLimits(c, c.panMin(), c.panMax(), c.tiltMin(), c.tiltMax() + delta(e, 5));
            refresh();
        });

        set(45, icon(Material.ARROW, line("Back", NamedTextColor.WHITE), List.of()), e -> back.open());
        set(49, icon(Material.ENDER_EYE, line("View", NamedTextColor.GREEN), List.of()), e -> {
            viewer.closeInventory();
            cm.startViewing(viewer, cameraId);
        });
        set(53, icon(Material.TNT, line("Remove camera", NamedTextColor.RED),
                List.of(line("Shift-click to confirm", NamedTextColor.GRAY))), e -> {
            if (!e.isShiftClick()) return;
            cm.remove(cameraId, CameraRemoveEvent.Cause.COMMAND);
            back.open();
        });
    }

    private void cycleType(Camera c, int dir) {
        List<CameraType> types = new ArrayList<>(ctx.cameras().types().values());
        int i = Math.max(0, types.indexOf(c.type()));
        ctx.cameras().setType(c, types.get(Math.floorMod(i + dir, types.size())).key());
        refresh();
    }

    private void prompt(String what, String initial, java.util.function.Consumer<String> apply) {
        viewer.closeInventory();
        Dialogs.text(ctx.plugin(), viewer, Component.text(what + " for " + cameraId), Component.text(what), initial, 48,
                v -> {
                    if (!v.isBlank()) apply.accept(v);
                    open();
                });
    }

    private static float delta(InventoryClickEvent e, float step) {
        float d = e.getClick().isRightClick() ? -step : step;
        return e.isShiftClick() ? d * 3 : d;
    }

    private static org.bukkit.inventory.ItemStack button(Material m, String name, String value, String hint) {
        return icon(m, line(name + ": " + value, NamedTextColor.WHITE), List.of(line(hint, NamedTextColor.GRAY)));
    }

    private static org.bukkit.inventory.ItemStack step(Material m, String name, float value, float step) {
        return icon(m, line(name + ": " + Math.round(value), NamedTextColor.WHITE), List.of(
                line("Click: +" + Math.round(step) + "   Right-click: -" + Math.round(step), NamedTextColor.GRAY),
                line("Shift: x3", NamedTextColor.GRAY)));
    }
}

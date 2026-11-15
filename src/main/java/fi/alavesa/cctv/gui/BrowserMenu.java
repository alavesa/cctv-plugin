package fi.alavesa.cctv.gui;

import fi.alavesa.cctv.api.CameraState;
import fi.alavesa.cctv.camera.Camera;
import fi.alavesa.cctv.camera.CameraManager;
import fi.alavesa.cctv.config.Messages;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.jspecify.annotations.Nullable;

/**
 * Camera network browser. Top row: network tabs and search. Middle: cameras with a status icon (online, damaged,
 * offline, unpowered, disabled, alert). Bottom: paging, status filter, close (and, in admin mode, placing items).
 * View mode (terminals, /cctv browse): click a camera to watch it. Admin mode (/cctv admin): click to edit,
 * right-click to watch.
 */
public final class BrowserMenu extends Menu {

    enum Filter { ALL, ONLINE, OFFLINE }

    private static final int PER_PAGE = 36;

    private final GuiContext ctx;
    private final boolean admin;
    private final @Nullable String lockedGroup;
    /** opened at a security terminal: cameras watched from here can be controlled */
    private final boolean terminal;
    private @Nullable String group;
    private Filter filter = Filter.ALL;
    private String search = "";
    private int page;

    public BrowserMenu(GuiContext ctx, Player viewer, boolean admin, @Nullable String lockedGroup) {
        this(ctx, viewer, admin, lockedGroup, false);
    }

    public BrowserMenu(GuiContext ctx, Player viewer, boolean admin, @Nullable String lockedGroup, boolean terminal) {
        super(viewer);
        this.terminal = terminal;
        this.ctx = ctx;
        this.admin = admin;
        this.lockedGroup = lockedGroup;
        this.group = lockedGroup;
    }

    @Override
    protected Component title() {
        String t = admin ? "CCTV ADMIN" : lockedGroup != null ? "CCTV - " + lockedGroup.toUpperCase(Locale.ROOT)
                : "CCTV NETWORK";
        return GuiTitle.of(t, ctx.packLoaded().test(viewer));
    }

    @Override
    protected void render() {
        // network tabs
        if (lockedGroup == null) {
            set(0, icon(group == null ? Material.LIME_DYE : Material.GRAY_DYE, line("All networks", NamedTextColor.WHITE),
                    List.of()), e -> {
                group = null;
                page = 0;
                refresh();
            });
            int slot = 1;
            for (String g : ctx.cameras().groups()) {
                if (slot > 7) break;
                boolean on = g.equalsIgnoreCase(group);
                String gg = g;
                set(slot++, icon(on ? Material.LIME_DYE : Material.GRAY_DYE, line(g, NamedTextColor.WHITE),
                        List.of(line(ctx.cameras().camerasInGroup(g).size() + " cameras", NamedTextColor.GRAY))), e -> {
                    group = gg;
                    page = 0;
                    refresh();
                });
            }
        }
        set(8, icon(Material.SPYGLASS, line(search.isEmpty() ? "Search" : "Search: " + search, NamedTextColor.WHITE),
                List.of(line("Click: type a search", NamedTextColor.GRAY),
                        line("Right-click: clear", NamedTextColor.GRAY))), e -> {
            if (e.getClick() == ClickType.RIGHT) {
                search = "";
                page = 0;
                refresh();
                return;
            }
            viewer.closeInventory();
            Dialogs.text(ctx.plugin(), viewer, Component.text("Search cameras"), Component.text("Id or name"), search,
                    32, v -> {
                        search = v.toLowerCase(Locale.ROOT);
                        page = 0;
                        open();
                    });
        });

        List<Camera> list = cameras();
        int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.min(page, pages - 1);
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = page * PER_PAGE + i;
            if (idx >= list.size()) break;
            Camera c = list.get(idx);
            set(9 + i, cameraIcon(c), e -> clickCamera(c, e.getClick()));
        }

        if (page > 0) set(45, icon(Material.ARROW, line("Previous page", NamedTextColor.WHITE), List.of()), e -> {
            page--;
            refresh();
        });
        set(47, icon(Material.HOPPER, line("Show: " + filter.name().toLowerCase(Locale.ROOT), NamedTextColor.WHITE),
                List.of(line("Click to change", NamedTextColor.GRAY))), e -> {
            filter = Filter.values()[(filter.ordinal() + 1) % Filter.values().length];
            page = 0;
            refresh();
        });
        set(49, icon(Material.BOOK, line("Page " + (page + 1) + "/" + pages, NamedTextColor.WHITE),
                List.of(line(list.size() + " cameras", NamedTextColor.GRAY))), null);
        if (admin) {
            set(51, icon(Material.CHEST, line("Place cameras, monitors, terminals", NamedTextColor.WHITE),
                    List.of(line("Get placement items", NamedTextColor.GRAY))), e -> new PlaceMenu(ctx, viewer, this).open());
        }
        set(50, icon(Material.BARRIER, line("Close", NamedTextColor.RED), List.of()), e -> viewer.closeInventory());
        if (page < pages - 1) set(53, icon(Material.ARROW, line("Next page", NamedTextColor.WHITE), List.of()), e -> {
            page++;
            refresh();
        });
    }

    private List<Camera> cameras() {
        List<Camera> out = new ArrayList<>();
        for (Camera c : ctx.cameras().cameras()) {
            if (group != null && !c.group().equalsIgnoreCase(group)) continue;
            boolean up = c.state().viewable();
            if (filter == Filter.ONLINE && !up || filter == Filter.OFFLINE && up) continue;
            if (!search.isEmpty() && !c.id().contains(search) && !c.name().toLowerCase(Locale.ROOT).contains(search)
                    && !c.group().contains(search)) continue;
            out.add(c);
        }
        out.sort((a, b) -> {
            int g = a.group().compareTo(b.group());
            return g != 0 ? g : CameraManager.naturalCompare(a.id(), b.id());
        });
        return out;
    }

    private org.bukkit.inventory.ItemStack cameraIcon(Camera c) {
        CameraState st = c.state();
        boolean alert = ctx.alerting().apply(c.id());
        String model = alert ? "alert" : switch (st) {
            case ONLINE -> "online";
            case DAMAGED -> "damaged";
            case DESTROYED -> "offline";
            case UNPOWERED -> "unpowered";
            case DISABLED -> "disabled";
        };
        boolean access = ctx.cameras().canAccess(viewer, c);
        List<Component> lore = new ArrayList<>();
        lore.add(line(c.id() + "  [" + c.group() + "]  " + c.type().key(), NamedTextColor.DARK_GRAY));
        lore.add(line(alert ? "ALERT: " + c.alertZone() : st.name(), alert ? NamedTextColor.RED
                : st.viewable() ? NamedTextColor.GREEN : NamedTextColor.RED));
        lore.add(line("Health " + Math.round(c.health()) + "/" + Math.round(c.maxHealth()), NamedTextColor.GRAY));
        if (c.clearance() > 0) lore.add(line("Clearance " + c.clearance(), NamedTextColor.GRAY));
        lore.add(Component.empty());
        if (admin) {
            lore.add(line("Click: edit", NamedTextColor.YELLOW));
            lore.add(line("Right-click: view", NamedTextColor.YELLOW));
        } else if (!access) {
            lore.add(line("ACCESS DENIED", NamedTextColor.RED));
        } else if (st.viewable()) {
            lore.add(line("Click: view", NamedTextColor.YELLOW));
        }
        return icon(NamespacedKey.fromString("cctv:gui_cam_" + model), line(c.name(), NamedTextColor.WHITE), lore);
    }

    private void clickCamera(Camera c, ClickType click) {
        if (admin && click != ClickType.RIGHT) {
            new EditorMenu(ctx, viewer, c.id(), this).open();
            return;
        }
        if (!ctx.cameras().canAccess(viewer, c)) {
            ctx.messages().get().send(viewer, "view-denied", Messages.p("id", c.id()), Messages.p("clearance", c.clearance()));
            return;
        }
        if (!c.state().viewable()) {
            ctx.messages().get().send(viewer, "view-offline", Messages.p("id", c.id()), Messages.p("state", c.state().name()));
            return;
        }
        viewer.closeInventory();
        ctx.cameras().startViewing(viewer, c.id(), terminal);
    }
}

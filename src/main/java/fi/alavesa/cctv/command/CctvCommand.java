package fi.alavesa.cctv.command;

import fi.alavesa.cctv.api.CameraState;
import fi.alavesa.cctv.api.CameraType;
import fi.alavesa.cctv.api.event.CameraRemoveEvent;
import fi.alavesa.cctv.camera.Camera;
import fi.alavesa.cctv.camera.CameraManager;
import fi.alavesa.cctv.camera.Placement;
import fi.alavesa.cctv.config.Messages;
import fi.alavesa.cctv.monitor.MonitorManager;
import fi.alavesa.cctv.pack.PackService;
import fi.alavesa.cctv.view.ViewManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * /cctv admin command. Every sub-command has its own permission: cctv.command.&lt;sub&gt;.
 * Camera ids may be omitted where marked [id]: the camera you are looking at (within 8 blocks) is used.
 */
public final class CctvCommand implements BasicCommand {

    private static final List<String> SUBS = List.of("give", "create", "remove", "list", "info", "rename", "setgroup",
            "tp", "power", "enable", "repair", "damage", "aim", "set", "view", "exit", "pack", "monitor", "perf", "browse", "admin", "terminal", "zone", "reload", "help");
    private static final List<String> SET_KEYS = List.of("fov", "clearance", "pan-min", "pan-max", "tilt-min",
            "tilt-max", "yaw", "pitch", "type");
    private static final int PAGE = 8;

    private final CameraManager cameras;
    private final ViewManager views;
    private final PackService packs;
    private final Supplier<Messages> messages;
    private final Runnable reload;
    private final MonitorCommand monitorCommand;
    private fi.alavesa.cctv.gui.@Nullable GuiContext gui;
    private fi.alavesa.cctv.terminal.@Nullable TerminalManager terminals;
    private fi.alavesa.cctv.alert.@Nullable AlertService alerts;

    /** Phase-6 services, set after construction. */
    public void extras(fi.alavesa.cctv.gui.GuiContext gui, fi.alavesa.cctv.terminal.TerminalManager terminals,
                       fi.alavesa.cctv.alert.AlertService alerts) {
        this.gui = gui;
        this.terminals = terminals;
        this.alerts = alerts;
    }

    public CctvCommand(CameraManager cameras, ViewManager views, PackService packs, MonitorManager monitors,
                       Supplier<Messages> messages, Runnable reload) {
        this.monitorCommand = new MonitorCommand(monitors, cameras, messages);
        this.cameras = cameras;
        this.views = views;
        this.packs = packs;
        this.messages = messages;
        this.reload = reload;
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        // "execute as <player> run cctv ..." (command blocks, functions) acts as that player
        CommandSender s = stack.getExecutor() instanceof Player p ? p : stack.getSender();
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (!SUBS.contains(sub)) {
            msg(s, "unknown-subcommand", Messages.p("sub", sub));
            return;
        }
        if (!sub.equals("help") && !s.hasPermission("cctv.command." + sub)) {
            msg(s, "no-permission");
            return;
        }
        String[] a = Arrays.copyOfRange(args, Math.min(1, args.length), args.length);
        try {
            switch (sub) {
                case "give" -> give(s, a);
                case "create" -> create(s, a);
                case "remove" -> remove(s, a);
                case "list" -> list(s, a);
                case "info" -> info(s, a);
                case "rename" -> rename(s, a);
                case "setgroup" -> setGroup(s, a);
                case "tp" -> tp(s, a);
                case "power" -> power(s, a);
                case "enable" -> enable(s, a);
                case "repair" -> repair(s, a);
                case "damage" -> damage(s, a);
                case "aim" -> aim(s, a);
                case "set" -> set(s, a);
                case "view" -> view(s, a);
                case "exit" -> exit(s, a);
                case "pack" -> pack(s, a);
                case "monitor" -> monitorCommand.execute(s, a);
                case "perf" -> monitorCommand.perf(s);
                case "browse" -> browse(s, a, false);
                case "admin" -> browse(s, a, true);
                case "terminal" -> terminal(s, a);
                case "zone" -> zone(s, a);
                case "reload" -> {
                    reload.run();
                    msg(s, "reloaded", Messages.p("count", cameras.cameras().size()));
                }
                default -> help(s);
            }
        } catch (NumberFormatException e) {
            msg(s, "bad-number", Messages.p("value", e.getMessage() == null ? "?" : e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ sub-commands

    /** give &lt;type&gt; [player] [amount] [group] */
    private void give(CommandSender s, String[] a) {
        if (a.length < 1) {
            usage(s, "/cctv give <type> [player] [amount] [group]");
            return;
        }
        CameraType type = cameras.types().get(a[0].toLowerCase(Locale.ROOT));
        if (type == null) {
            msg(s, "unknown-type", Messages.p("type", a[0]));
            return;
        }
        Player target = a.length >= 2 ? Bukkit.getPlayerExact(a[1]) : s instanceof Player p ? p : null;
        if (target == null) {
            msg(s, "unknown-player", Messages.p("player", a.length >= 2 ? a[1] : "-"));
            return;
        }
        int amount = a.length >= 3 ? Integer.parseInt(a[2]) : 1;
        String group = a.length >= 4 ? a[3].toLowerCase(Locale.ROOT) : "default";
        target.getInventory().addItem(cameras.items().placer(type, amount, group))
                .values().forEach(left -> target.getWorld().dropItem(target.getLocation(), left));
        msg(s, "given", Messages.p("type", type.key()), Messages.p("player", target.getName()),
                Messages.p("amount", amount));
    }

    /** create &lt;type&gt; [group] [name...] : on the block face you are looking at */
    private void create(CommandSender s, String[] a) {
        Player p = player(s);
        if (p == null) return;
        if (a.length < 1) {
            usage(s, "/cctv create <type> [group] [name...]");
            return;
        }
        CameraType type = cameras.types().get(a[0].toLowerCase(Locale.ROOT));
        if (type == null) {
            msg(s, "unknown-type", Messages.p("type", a[0]));
            return;
        }
        RayTraceResult hit = p.rayTraceBlocks(8, FluidCollisionMode.NEVER);
        Block block = hit == null ? null : hit.getHitBlock();
        BlockFace face = hit == null ? null : hit.getHitBlockFace();
        if (block == null || face == null) {
            msg(s, "look-at-block");
            return;
        }
        if (!Placement.fits(block, face)) {
            msg(s, "place-blocked");
            return;
        }
        Placement.Spec spec = Placement.compute(cameras.config(), block, face, p.getYaw());
        if (cameras.nearest(spec.eye(), cameras.config().minSpacing) != null) {
            msg(s, "place-too-close");
            return;
        }
        String group = a.length >= 2 ? a[1] : "default";
        String name = a.length >= 3 ? String.join(" ", Arrays.copyOfRange(a, 2, a.length)) : null;
        cameras.create(type.key(), spec.eye(), spec.mount(), spec.yaw(), spec.pitch(), name, group, p)
                .ifPresentOrElse(c -> msg(s, "placed", Messages.p("id", c.id()), Messages.p("group", c.group())),
                        () -> msg(s, "create-cancelled"));
    }

    /** remove [id] */
    private void remove(CommandSender s, String[] a) {
        Camera c = target(s, a, 0);
        if (c == null) return;
        cameras.remove(c.id(), CameraRemoveEvent.Cause.COMMAND);
        msg(s, "removed", Messages.p("id", c.id()));
    }

    /** list [group|*] [page] */
    private void list(CommandSender s, String[] a) {
        String group = a.length >= 1 && !a[0].equals("*") ? a[0] : null;
        int page = a.length >= 2 ? Math.max(1, Integer.parseInt(a[1])) : 1;
        List<Camera> all = new ArrayList<>();
        for (Camera c : cameras.cameras()) if (group == null || c.group().equalsIgnoreCase(group)) all.add(c);
        all.sort(Comparator.comparing(Camera::group).thenComparing(Camera::id, CameraManager::naturalCompare));
        int pages = Math.max(1, (all.size() + PAGE - 1) / PAGE);
        page = Math.min(page, pages);
        msg(s, "list-header", Messages.p("count", all.size()), Messages.p("group", group == null ? "*" : group),
                Messages.p("page", page), Messages.p("pages", pages));
        for (int i = (page - 1) * PAGE; i < Math.min(all.size(), page * PAGE); i++) {
            Camera c = all.get(i);
            s.sendMessage(Component.text()
                    .append(Component.text(" " + c.id(), NamedTextColor.AQUA))
                    .append(Component.text(" " + c.name(), NamedTextColor.WHITE))
                    .append(Component.text(" [" + c.group() + "] ", NamedTextColor.GRAY))
                    .append(Component.text(c.type().key() + " ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(c.state().name(), stateColor(c.state())))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to teleport")))
                    .clickEvent(ClickEvent.runCommand("/cctv tp " + c.id())));
        }
    }

    /** info [id] */
    private void info(CommandSender s, String[] a) {
        Camera c = target(s, a, 0);
        if (c == null) return;
        msg(s, "info",
                Messages.p("id", c.id()), Messages.p("name", c.name()), Messages.p("group", c.group()),
                Messages.p("type", c.typeKey()), Messages.p("state", c.state().name()),
                Messages.p("health", fmt(c.health())), Messages.p("max", fmt(c.maxHealth())),
                Messages.p("world", c.worldName()),
                Messages.p("pos", fmt(c.location().getX()) + " " + fmt(c.location().getY()) + " "
                        + fmt(c.location().getZ())),
                Messages.p("mount", c.mount().name()), Messages.p("yaw", fmt(c.yaw())), Messages.p("pitch", fmt(c.pitch())),
                Messages.p("pan", fmt(c.pan())), Messages.p("tilt", fmt(c.tilt())),
                Messages.p("pan_min", fmt(c.panMin())), Messages.p("pan_max", fmt(c.panMax())),
                Messages.p("tilt_min", fmt(c.tiltMin())), Messages.p("tilt_max", fmt(c.tiltMax())),
                Messages.p("fov", fmt(c.fov())), Messages.p("clearance", c.clearance()),
                Messages.p("powered", c.powered()), Messages.p("enabled", c.enabled()));
    }

    /** rename &lt;id&gt; &lt;name...&gt; */
    private void rename(CommandSender s, String[] a) {
        if (a.length < 2) {
            usage(s, "/cctv rename <id> <name...>");
            return;
        }
        Camera c = byId(s, a[0]);
        if (c == null) return;
        String name = String.join(" ", Arrays.copyOfRange(a, 1, a.length));
        cameras.rename(c.id(), name);
        msg(s, "renamed", Messages.p("id", c.id()), Messages.p("name", name));
    }

    /** setgroup &lt;id&gt; &lt;group&gt; */
    private void setGroup(CommandSender s, String[] a) {
        if (a.length < 2) {
            usage(s, "/cctv setgroup <id> <group>");
            return;
        }
        Camera c = byId(s, a[0]);
        if (c == null) return;
        cameras.setGroup(c.id(), a[1]);
        msg(s, "group-set", Messages.p("id", c.id()), Messages.p("group", c.group()));
    }

    /** tp &lt;id&gt; : stands you just in front of the lens, looking the same way */
    private void tp(CommandSender s, String[] a) {
        Player p = player(s);
        if (p == null) return;
        if (a.length < 1) {
            usage(s, "/cctv tp <id>");
            return;
        }
        Camera c = byId(s, a[0]);
        if (c == null) return;
        Location eye = c.location();
        if (eye.getWorld() == null) {
            msg(s, "world-not-loaded", Messages.p("world", c.worldName()));
            return;
        }
        Vector dir = eye.getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) dir = new Vector(0, 0, 1);
        Location to = eye.clone().add(dir.normalize().multiply(0.8)).subtract(0, p.getEyeHeight(), 0);
        to.setYaw(eye.getYaw());
        to.setPitch(eye.getPitch());
        p.teleportAsync(to).thenAccept(ok -> msg(s, ok ? "teleported" : "teleport-failed", Messages.p("id", c.id())));
    }

    /** power &lt;id|group:&lt;name&gt;&gt; &lt;on|off&gt; */
    private void power(CommandSender s, String[] a) {
        if (a.length < 2) {
            usage(s, "/cctv power <id|group:<name>> <on|off>");
            return;
        }
        boolean on = onOff(a[1]);
        if (a[0].toLowerCase(Locale.ROOT).startsWith("group:")) {
            String g = a[0].substring(6);
            int n = cameras.setGroupPowered(g, on);
            msg(s, "power-group", Messages.p("group", g), Messages.p("count", n), Messages.p("state", on ? "on" : "off"));
            return;
        }
        Camera c = byId(s, a[0]);
        if (c == null) return;
        cameras.setPowered(c.id(), on);
        msg(s, "power-set", Messages.p("id", c.id()), Messages.p("state", on ? "on" : "off"));
    }

    /** enable &lt;id&gt; &lt;on|off&gt; */
    private void enable(CommandSender s, String[] a) {
        if (a.length < 2) {
            usage(s, "/cctv enable <id> <on|off>");
            return;
        }
        Camera c = byId(s, a[0]);
        if (c == null) return;
        boolean on = onOff(a[1]);
        cameras.setEnabled(c.id(), on);
        msg(s, "enable-set", Messages.p("id", c.id()), Messages.p("state", on ? "on" : "off"));
    }

    /** repair [id] [amount] : full repair by default */
    private void repair(CommandSender s, String[] a) {
        Camera c = target(s, a, 0);
        if (c == null) return;
        double amount = a.length >= 2 ? Double.parseDouble(a[1]) : c.maxHealth();
        double h = cameras.repair(c, amount, s instanceof Player p ? p : null);
        msg(s, h < 0 ? "repair-cancelled" : "repaired", Messages.p("id", c.id()),
                Messages.p("health", fmt(Math.max(0, h))), Messages.p("max", fmt(c.maxHealth())));
    }

    /** damage &lt;id&gt; &lt;amount&gt; */
    private void damage(CommandSender s, String[] a) {
        if (a.length < 2) {
            usage(s, "/cctv damage <id> <amount>");
            return;
        }
        Camera c = byId(s, a[0]);
        if (c == null) return;
        double h = cameras.damage(c.id(), Double.parseDouble(a[1]), s instanceof Entity e ? e : null);
        msg(s, h < 0 ? "damage-cancelled" : "damaged", Messages.p("id", c.id()),
                Messages.p("health", fmt(Math.max(0, h))), Messages.p("max", fmt(c.maxHealth())));
    }

    /** aim &lt;id&gt; &lt;pan&gt; &lt;tilt&gt; : PTZ target (fixed cameras need cctv.command.set and use 'set yaw') */
    private void aim(CommandSender s, String[] a) {
        if (a.length < 3) {
            usage(s, "/cctv aim <id> <pan> <tilt>");
            return;
        }
        Camera c = byId(s, a[0]);
        if (c == null) return;
        if (!c.type().ptz()) {
            msg(s, "not-ptz", Messages.p("id", c.id()));
            return;
        }
        cameras.aimRaw(c, Float.parseFloat(a[1]), Float.parseFloat(a[2]));
        msg(s, "aimed", Messages.p("id", c.id()), Messages.p("pan", fmt(Math.clamp(Float.parseFloat(a[1]), c.panMin(),
                c.panMax()))), Messages.p("tilt", fmt(Math.clamp(Float.parseFloat(a[2]), c.tiltMin(), c.tiltMax()))));
    }

    /** set &lt;id&gt; &lt;key&gt; &lt;value&gt; */
    private void set(CommandSender s, String[] a) {
        if (a.length < 3) {
            usage(s, "/cctv set <id> <" + String.join("|", SET_KEYS) + "> <value>");
            return;
        }
        Camera c = byId(s, a[0]);
        if (c == null) return;
        String key = a[1].toLowerCase(Locale.ROOT);
        String v = a[2];
        switch (key) {
            case "fov" -> cameras.setFov(c, Float.parseFloat(v));
            case "clearance" -> cameras.setClearance(c, Integer.parseInt(v));
            case "pan-min" -> cameras.setLimits(c, Float.parseFloat(v), c.panMax(), c.tiltMin(), c.tiltMax());
            case "pan-max" -> cameras.setLimits(c, c.panMin(), Float.parseFloat(v), c.tiltMin(), c.tiltMax());
            case "tilt-min" -> cameras.setLimits(c, c.panMin(), c.panMax(), Float.parseFloat(v), c.tiltMax());
            case "tilt-max" -> cameras.setLimits(c, c.panMin(), c.panMax(), c.tiltMin(), Float.parseFloat(v));
            case "yaw" -> cameras.setBase(c, Float.parseFloat(v), c.basePitch());
            case "pitch" -> cameras.setBase(c, c.baseYaw(), Float.parseFloat(v));
            case "type" -> {
                if (!cameras.setType(c, v)) {
                    msg(s, "unknown-type", Messages.p("type", v));
                    return;
                }
            }
            default -> {
                usage(s, "/cctv set <id> <" + String.join("|", SET_KEYS) + "> <value>");
                return;
            }
        }
        // echo what was applied, not what was typed: values are clamped
        String applied = switch (key) {
            case "fov" -> fmt(c.fov());
            case "clearance" -> String.valueOf(c.clearance());
            case "pan-min", "pan-max" -> fmt(c.panMin()) + ".." + fmt(c.panMax());
            case "tilt-min", "tilt-max" -> fmt(c.tiltMin()) + ".." + fmt(c.tiltMax());
            case "yaw", "pitch" -> fmt(c.baseYaw()) + "/" + fmt(c.basePitch());
            default -> c.type().key();
        };
        msg(s, "value-set", Messages.p("id", c.id()), Messages.p("key", key), Messages.p("value", applied));
    }

    /** view &lt;id&gt; [player] : attach a player's view to a camera (the normal way in is a monitor or terminal) */
    private void view(CommandSender s, String[] a) {
        if (a.length < 1) {
            usage(s, "/cctv view <id> [player]");
            return;
        }
        Camera c = byId(s, a[0]);
        if (c == null) return;
        Player target = a.length >= 2 ? Bukkit.getPlayerExact(a[1]) : s instanceof Player p ? p : null;
        if (target == null) {
            msg(s, "unknown-player", Messages.p("player", a.length >= 2 ? a[1] : "-"));
            return;
        }
        if (views.start(target, c) && target != s) msg(s, "view-started", Messages.p("player", target.getName()),
                Messages.p("id", c.id()));
    }

    /** exit [player] */
    private void exit(CommandSender s, String[] a) {
        Player target = a.length >= 1 ? Bukkit.getPlayerExact(a[0]) : s instanceof Player p ? p : null;
        if (target == null) {
            msg(s, "unknown-player", Messages.p("player", a.length >= 1 ? a[0] : "-"));
            return;
        }
        if (!views.stop(target)) msg(s, "view-not-viewing", Messages.p("player", target.getName()));
    }

    /** pack [player] : pack status, or re-send it to a player */
    private void pack(CommandSender s, String[] a) {
        if (a.length >= 1) {
            Player target = Bukkit.getPlayerExact(a[0]);
            if (target == null) {
                msg(s, "unknown-player", Messages.p("player", a[0]));
                return;
            }
            packs.send(target);
            msg(s, "pack-sent", Messages.p("player", target.getName()));
            return;
        }
        msg(s, "pack-info", Messages.p("sha1", String.valueOf(packs.sha1())),
                Messages.p("backend", views.effects().name()), Messages.p("viewers", views.count()));
        for (Player p : Bukkit.getOnlinePlayers()) {
            var st = packs.status(p);
            msg(s, "pack-player", Messages.p("player", p.getName()), Messages.p("status", st == null ? "-" : st.name()),
                    Messages.p("loaded", packs.loaded(p)));
        }
    }

    /** browse [player] / admin : the camera browser in view or admin mode */
    private void browse(CommandSender s, String[] a, boolean admin) {
        if (gui == null) return;
        Player target = a.length >= 1 && s.hasPermission("cctv.command.admin") ? Bukkit.getPlayerExact(a[0])
                : s instanceof Player p ? p : null;
        if (target == null) {
            msg(s, a.length >= 1 ? "unknown-player" : "players-only", Messages.p("player", a.length >= 1 ? a[0] : "-"));
            return;
        }
        new fi.alavesa.cctv.gui.BrowserMenu(gui, target, admin, null).open();
    }

    /** terminal give [player] | group &lt;id|here&gt; &lt;network|*&gt; | clearance &lt;id|here&gt; &lt;n&gt; | remove &lt;id|here&gt; | list */
    private void terminal(CommandSender s, String[] a) {
        if (terminals == null) return;
        String sub = a.length == 0 ? "list" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "give" -> {
                Player target = a.length >= 2 ? Bukkit.getPlayerExact(a[1]) : s instanceof Player p ? p : null;
                if (target == null) {
                    msg(s, "unknown-player", Messages.p("player", a.length >= 2 ? a[1] : "-"));
                    return;
                }
                target.getInventory().addItem(terminals.placer());
                msg(s, "given", Messages.p("type", "terminal"), Messages.p("player", target.getName()),
                        Messages.p("amount", 1));
            }
            case "group", "clearance", "remove" -> {
                if (a.length < (sub.equals("remove") ? 2 : 3)) {
                    usage(s, "/cctv terminal " + sub + " <id|here>" + (sub.equals("remove") ? "" : " <value>"));
                    return;
                }
                var t = terminalTarget(s, a[1]);
                if (t == null) return;
                switch (sub) {
                    case "group" -> terminals.setGroup(t, a[2]);
                    case "clearance" -> terminals.setClearance(t, Integer.parseInt(a[2]));
                    default -> terminals.remove(t);
                }
                msg(s, "value-set", Messages.p("id", t.id()), Messages.p("key", sub),
                        Messages.p("value", sub.equals("remove") ? "removed" : a[2]));
            }
            default -> {
                msg(s, "terminal-list-header", Messages.p("count", terminals.terminals().size()));
                for (var t : terminals.terminals()) {
                    s.sendMessage(Component.text(" " + t.id() + "  network " + (t.group() == null ? "*" : t.group())
                            + "  clearance " + t.clearance(), NamedTextColor.GRAY));
                }
            }
        }
    }

    private fi.alavesa.cctv.terminal.@Nullable Terminal terminalTarget(CommandSender s, String idOrHere) {
        if (terminals == null) return null;
        if (!idOrHere.equalsIgnoreCase("here")) {
            var t = terminals.get(idOrHere);
            if (t == null) msg(s, "unknown-terminal", Messages.p("id", idOrHere));
            return t;
        }
        if (s instanceof Player p) {
            Block b = p.getTargetBlockExact(6);
            var t = b == null ? null : terminals.at(b);
            if (t != null) return t;
        }
        msg(s, "unknown-terminal", Messages.p("id", "here"));
        return null;
    }

    /** zone pos1 | pos2 | create &lt;name&gt; &lt;network&gt; | remove &lt;name&gt; | list */
    private void zone(CommandSender s, String[] a) {
        if (alerts == null) return;
        String sub = a.length == 0 ? "list" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "pos1", "pos2" -> {
                Player p = player(s);
                if (p == null) return;
                alerts.select(p, sub.equals("pos1") ? 1 : 2);
                msg(s, "zone-pos", Messages.p("n", sub.substring(3)), Messages.p("pos",
                        p.getLocation().getBlockX() + " " + p.getLocation().getBlockY() + " " + p.getLocation().getBlockZ()));
            }
            case "create" -> {
                Player p = player(s);
                if (p == null) return;
                if (a.length < 3) {
                    usage(s, "/cctv zone create <name> <network>");
                    return;
                }
                String err = alerts.create(p, a[1], a[2]);
                if (err != null) msg(s, err);
                else msg(s, "zone-created", Messages.p("name", a[1]), Messages.p("group", a[2]));
            }
            case "remove" -> {
                if (a.length < 2) {
                    usage(s, "/cctv zone remove <name>");
                    return;
                }
                msg(s, alerts.remove(a[1]) ? "zone-removed" : "zone-unknown", Messages.p("name", a[1]));
            }
            default -> {
                msg(s, "zone-list-header", Messages.p("count", alerts.zones().size()));
                for (var z : alerts.zones()) {
                    s.sendMessage(Component.text(" " + z.name() + "  network " + z.group() + "  " + z.world() + " "
                            + (int) z.box().getMinX() + "," + (int) z.box().getMinY() + "," + (int) z.box().getMinZ()
                            + " .. " + (int) z.box().getMaxX() + "," + (int) z.box().getMaxY() + ","
                            + (int) z.box().getMaxZ(), NamedTextColor.GRAY));
                }
            }
        }
    }

    private void help(CommandSender s) {
        msg(s, "help-header");
        for (String sub : SUBS) {
            if (!sub.equals("help") && s.hasPermission("cctv.command." + sub)) msg(s, "help-" + sub);
        }
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public Collection<String> suggest(CommandSourceStack stack, String[] args) {
        CommandSender s = stack.getSender();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0];
            return filter(SUBS.stream().filter(sub -> sub.equals("help") || s.hasPermission("cctv.command." + sub)),
                    prefix);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!s.hasPermission("cctv.command." + sub)) return List.of();
        String cur = args[args.length - 1];
        int pos = args.length - 1;
        return switch (sub) {
            case "give" -> switch (pos) {
                case 1 -> filter(cameras.types().keySet().stream(), cur);
                case 2 -> filter(Bukkit.getOnlinePlayers().stream().map(Player::getName), cur);
                case 3 -> filter(Stream.of("1", "16", "64"), cur);
                case 4 -> filter(cameras.groups().stream(), cur);
                default -> List.of();
            };
            case "create" -> switch (pos) {
                case 1 -> filter(cameras.types().keySet().stream(), cur);
                case 2 -> filter(cameras.groups().stream(), cur);
                default -> List.of();
            };
            case "list" -> pos == 1 ? filter(Stream.concat(Stream.of("*"), cameras.groups().stream()), cur) : List.of();
            case "power" -> switch (pos) {
                case 1 -> filter(Stream.concat(ids(), cameras.groups().stream().map(g -> "group:" + g)), cur);
                case 2 -> filter(Stream.of("on", "off"), cur);
                default -> List.of();
            };
            case "enable" -> pos == 1 ? filter(ids(), cur) : pos == 2 ? filter(Stream.of("on", "off"), cur) : List.of();
            case "setgroup" -> pos == 1 ? filter(ids(), cur) : pos == 2 ? filter(cameras.groups().stream(), cur)
                    : List.of();
            case "set" -> switch (pos) {
                case 1 -> filter(ids(), cur);
                case 2 -> filter(SET_KEYS.stream(), cur);
                case 3 -> args[2].equalsIgnoreCase("type") ? filter(cameras.types().keySet().stream(), cur) : List.of();
                default -> List.of();
            };
            case "remove", "info", "rename", "tp", "repair", "damage", "aim" -> pos == 1 ? filter(ids(), cur) : List.of();
            case "view" -> pos == 1 ? filter(ids(), cur)
                    : pos == 2 ? filter(Bukkit.getOnlinePlayers().stream().map(Player::getName), cur) : List.of();
            case "monitor" -> monitorCommand.suggest(java.util.Arrays.copyOfRange(args, 1, args.length));
            case "terminal" -> pos == 1 ? filter(Stream.of("give", "group", "clearance", "remove", "list"), cur)
                    : pos == 2 && terminals != null ? filter(Stream.concat(Stream.of("here"),
                    terminals.terminals().stream().map(t -> t.id())), cur)
                    : pos == 3 && args[1].equalsIgnoreCase("group") ? filter(Stream.concat(Stream.of("*"),
                    cameras.groups().stream()), cur) : List.of();
            case "zone" -> pos == 1 ? filter(Stream.of("pos1", "pos2", "create", "remove", "list"), cur)
                    : pos == 2 && args[1].equalsIgnoreCase("remove") && alerts != null
                    ? filter(alerts.zones().stream().map(z -> z.name()), cur)
                    : pos == 3 && args[1].equalsIgnoreCase("create") ? filter(cameras.groups().stream(), cur)
                    : List.of();
            case "browse" -> pos == 1 ? filter(Bukkit.getOnlinePlayers().stream().map(Player::getName), cur) : List.of();
            case "exit", "pack" -> pos == 1 ? filter(Bukkit.getOnlinePlayers().stream().map(Player::getName), cur)
                    : List.of();
            default -> List.of();
        };
    }

    // ------------------------------------------------------------------ helpers

    private Stream<String> ids() {
        return cameras.cameras().stream().map(Camera::id);
    }

    private static List<String> filter(Stream<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.filter(o -> o.toLowerCase(Locale.ROOT).startsWith(p)).sorted().toList();
    }

    /** Camera from args[index], or the one the player is looking at if the arg is missing. */
    private @Nullable Camera target(CommandSender s, String[] a, int index) {
        if (a.length > index) return byId(s, a[index]);
        if (s instanceof Player p) {
            RayTraceResult r = p.getWorld().rayTraceEntities(p.getEyeLocation(), p.getEyeLocation().getDirection(), 8,
                    0.1, e -> cameras.cameraOf(e) != null);
            if (r != null && r.getHitEntity() != null) {
                Camera c = cameras.cameraOf(r.getHitEntity());
                if (c != null) return c;
            }
        }
        msg(s, "need-id");
        return null;
    }

    private @Nullable Camera byId(CommandSender s, String id) {
        Camera c = cameras.get(id);
        if (c == null) msg(s, "unknown-camera", Messages.p("id", id));
        return c;
    }

    private @Nullable Player player(CommandSender s) {
        if (s instanceof Player p) return p;
        msg(s, "players-only");
        return null;
    }

    private static boolean onOff(String v) {
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "on", "true", "1", "yes" -> true;
            case "off", "false", "0", "no" -> false;
            default -> throw new NumberFormatException(v);
        };
    }

    private void usage(CommandSender s, String usage) {
        msg(s, "usage", Messages.p("usage", usage));
    }

    private void msg(CommandSender s, String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... r) {
        messages.get().send(s, key, r);
    }

    private static NamedTextColor stateColor(CameraState st) {
        return switch (st) {
            case ONLINE -> NamedTextColor.GREEN;
            case DAMAGED -> NamedTextColor.YELLOW;
            case UNPOWERED -> NamedTextColor.GOLD;
            case DESTROYED -> NamedTextColor.RED;
            case DISABLED -> NamedTextColor.DARK_GRAY;
        };
    }

    private static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }
}

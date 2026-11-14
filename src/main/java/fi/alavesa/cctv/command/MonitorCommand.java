package fi.alavesa.cctv.command;

import fi.alavesa.cctv.camera.CameraManager;
import fi.alavesa.cctv.config.Messages;
import fi.alavesa.cctv.monitor.Monitor;
import fi.alavesa.cctv.monitor.MonitorConfig;
import fi.alavesa.cctv.monitor.MonitorManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.jspecify.annotations.Nullable;

/**
 * /cctv monitor ... (permission cctv.command.monitor) and /cctv perf (cctv.command.perf).
 * [id] = the monitor you look at when omitted.
 */
final class MonitorCommand {

    static final List<String> SUBS = List.of("give", "bind", "cycle", "mode", "quad", "power", "remove", "list",
            "info");

    private final MonitorManager monitors;
    private final CameraManager cameras;
    private final Supplier<Messages> messages;

    MonitorCommand(MonitorManager monitors, CameraManager cameras, Supplier<Messages> messages) {
        this.monitors = monitors;
        this.cameras = cameras;
        this.messages = messages;
    }

    void execute(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "list" : a[0].toLowerCase(Locale.ROOT);
        String[] r = Arrays.copyOfRange(a, Math.min(1, a.length), a.length);
        switch (sub) {
            case "give" -> give(s, r);
            case "bind" -> bind(s, r, 0);
            case "cycle" -> cycle(s, r);
            case "quad" -> quad(s, r);
            case "mode" -> mode(s, r);
            case "power" -> power(s, r);
            case "remove" -> remove(s, r);
            case "info" -> info(s, r);
            case "list" -> list(s);
            default -> msg(s, "usage", Messages.p("usage", "/cctv monitor <" + String.join("|", SUBS) + ">"));
        }
    }

    void perf(CommandSender s) {
        msg(s, "perf-header");
        for (String line : monitors.perf()) s.sendMessage(Component.text(" " + line, NamedTextColor.GRAY));
    }

    /** give &lt;type&gt; [player] [amount] */
    private void give(CommandSender s, String[] a) {
        if (a.length < 1) {
            msg(s, "usage", Messages.p("usage", "/cctv monitor give <type> [player] [amount]"));
            return;
        }
        MonitorConfig.Type type = monitors.config().types.get(a[0].toLowerCase(Locale.ROOT));
        if (type == null) {
            msg(s, "unknown-monitor-type", Messages.p("type", a[0]));
            return;
        }
        Player target = a.length >= 2 ? Bukkit.getPlayerExact(a[1]) : s instanceof Player p ? p : null;
        if (target == null) {
            msg(s, "unknown-player", Messages.p("player", a.length >= 2 ? a[1] : "-"));
            return;
        }
        int amount = a.length >= 3 ? Integer.parseInt(a[2]) : 1;
        target.getInventory().addItem(monitors.placer(type, amount))
                .values().forEach(left -> target.getWorld().dropItem(target.getLocation(), left));
        msg(s, "given", Messages.p("type", type.key()), Messages.p("player", target.getName()),
                Messages.p("amount", amount));
    }

    /** bind &lt;monitor|here&gt; &lt;camera&gt; */
    private void bind(CommandSender s, String[] a, int cycle) {
        if (a.length < 2) {
            msg(s, "usage", Messages.p("usage", "/cctv monitor bind <monitor|here> <camera>"));
            return;
        }
        Monitor m = target(s, a[0]);
        if (m == null || !camerasExist(s, List.of(a[1]))) return;
        monitors.bind(m, List.of(a[1]), cycle);
        if (m.mode() == Monitor.Mode.QUAD) monitors.setMode(m, Monitor.Mode.LIVE);
        msg(s, "monitor-bound", Messages.p("id", m.id()), Messages.p("cameras", a[1]));
    }

    /** cycle &lt;monitor|here&gt; &lt;seconds&gt; &lt;camera...&gt;  (cameras comma or space separated; group:&lt;name&gt; for a whole group) */
    private void cycle(CommandSender s, String[] a) {
        if (a.length < 3) {
            msg(s, "usage", Messages.p("usage", "/cctv monitor cycle <monitor|here> <seconds> <camera...|group:name>"));
            return;
        }
        Monitor m = target(s, a[0]);
        if (m == null) return;
        int secs = Integer.parseInt(a[1]);
        List<String> ids = cameraList(Arrays.copyOfRange(a, 2, a.length));
        if (!camerasExist(s, ids)) return;
        monitors.bind(m, ids, Math.max(2, secs));
        if (m.mode() == Monitor.Mode.QUAD) monitors.setMode(m, Monitor.Mode.LIVE);
        msg(s, "monitor-bound", Messages.p("id", m.id()), Messages.p("cameras", String.join(", ", ids)));
    }

    /** quad &lt;monitor|here&gt; &lt;c1&gt; [c2] [c3] [c4] */
    private void quad(CommandSender s, String[] a) {
        if (a.length < 2) {
            msg(s, "usage", Messages.p("usage", "/cctv monitor quad <monitor|here> <c1> [c2] [c3] [c4]"));
            return;
        }
        Monitor m = target(s, a[0]);
        if (m == null) return;
        List<String> ids = cameraList(Arrays.copyOfRange(a, 1, a.length));
        if (ids.size() > 4) ids = ids.subList(0, 4);
        if (!camerasExist(s, ids)) return;
        monitors.bind(m, ids, 0);
        monitors.setMode(m, Monitor.Mode.QUAD);
        msg(s, "monitor-bound", Messages.p("id", m.id()), Messages.p("cameras", "quad " + String.join(", ", ids)));
    }

    /** mode &lt;monitor|here&gt; &lt;live|snapshot|shader|quad&gt; */
    private void mode(CommandSender s, String[] a) {
        if (a.length < 2) {
            msg(s, "usage", Messages.p("usage", "/cctv monitor mode <monitor|here> <live|snapshot|shader|quad>"));
            return;
        }
        Monitor m = target(s, a[0]);
        if (m == null) return;
        Monitor.Mode mode;
        try {
            mode = Monitor.Mode.valueOf(a[1].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            msg(s, "bad-number", Messages.p("value", a[1]));
            return;
        }
        monitors.setMode(m, mode);
        msg(s, "value-set", Messages.p("id", m.id()), Messages.p("key", "mode"), Messages.p("value", mode.name()));
    }

    /** power &lt;monitor|here&gt; &lt;on|off&gt; */
    private void power(CommandSender s, String[] a) {
        if (a.length < 2) {
            msg(s, "usage", Messages.p("usage", "/cctv monitor power <monitor|here> <on|off>"));
            return;
        }
        Monitor m = target(s, a[0]);
        if (m == null) return;
        boolean on = a[1].equalsIgnoreCase("on") || a[1].equalsIgnoreCase("true");
        monitors.setPowered(m, on);
        msg(s, "power-set", Messages.p("id", m.id()), Messages.p("state", on ? "on" : "off"));
    }

    private void remove(CommandSender s, String[] a) {
        Monitor m = target(s, a.length > 0 ? a[0] : "here");
        if (m == null) return;
        monitors.remove(m.id());
        msg(s, "monitor-removed", Messages.p("id", m.id()));
    }

    private void info(CommandSender s, String[] a) {
        Monitor m = target(s, a.length > 0 ? a[0] : "here");
        if (m == null) return;
        msg(s, "monitor-info", Messages.p("id", m.id()), Messages.p("type", m.type().key()),
                Messages.p("size", m.type().width() + "x" + m.type().height()), Messages.p("mode", m.mode().name()),
                Messages.p("powered", m.powered()),
                Messages.p("cameras", m.cameras().isEmpty() ? "-" : String.join(", ", m.cameras())));
    }

    private void list(CommandSender s) {
        msg(s, "monitor-list-header", Messages.p("count", monitors.monitors().size()));
        for (Monitor m : monitors.monitors()) {
            s.sendMessage(Component.text(" " + m.id() + " " + m.type().key() + " " + m.mode() + " "
                    + (m.cameras().isEmpty() ? "-" : String.join(",", m.cameras())) + (m.powered() ? "" : " (off)"),
                    NamedTextColor.GRAY));
        }
    }

    // ------------------------------------------------------------------ helpers

    private @Nullable Monitor target(CommandSender s, String idOrHere) {
        if (!idOrHere.equalsIgnoreCase("here")) {
            Monitor m = monitors.get(idOrHere);
            if (m == null) msg(s, "unknown-monitor", Messages.p("id", idOrHere));
            return m;
        }
        if (s instanceof Player p) {
            RayTraceResult r = p.getWorld().rayTraceEntities(p.getEyeLocation(), p.getEyeLocation().getDirection(), 8,
                    0.2, e -> monitors.of(e) != null);
            if (r != null && r.getHitEntity() != null) return monitors.of(r.getHitEntity());
        }
        msg(s, "need-monitor");
        return null;
    }

    private List<String> cameraList(String[] args) {
        List<String> out = new ArrayList<>();
        for (String a : args) {
            for (String part : a.split(",")) {
                if (part.isBlank()) continue;
                if (part.toLowerCase(Locale.ROOT).startsWith("group:")) {
                    cameras.camerasInGroup(part.substring(6)).stream()
                            .map(c -> c.id()).sorted(CameraManager::naturalCompare).forEach(out::add);
                } else {
                    out.add(part.toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    private boolean camerasExist(CommandSender s, List<String> ids) {
        if (ids.isEmpty()) {
            msg(s, "unknown-camera", Messages.p("id", "-"));
            return false;
        }
        for (String id : ids) {
            if (cameras.get(id) == null) {
                msg(s, "unknown-camera", Messages.p("id", id));
                return false;
            }
        }
        return true;
    }

    List<String> suggest(String[] args) {
        if (args.length <= 1) return filter(SUBS.stream(), args.length == 0 ? "" : args[0]);
        String sub = args[0].toLowerCase(Locale.ROOT);
        String cur = args[args.length - 1];
        int pos = args.length - 1;
        Stream<String> ids = Stream.concat(Stream.of("here"), monitors.monitors().stream().map(Monitor::id));
        Stream<String> cams = cameras.cameras().stream().map(c -> c.id());
        return switch (sub) {
            case "give" -> pos == 1 ? filter(monitors.config().types.keySet().stream(), cur)
                    : pos == 2 ? filter(Bukkit.getOnlinePlayers().stream().map(Player::getName), cur) : List.of();
            case "bind" -> pos == 1 ? filter(ids, cur) : pos == 2 ? filter(cams, cur) : List.of();
            case "cycle" -> pos == 1 ? filter(ids, cur) : pos == 2 ? filter(Stream.of("5", "8", "15"), cur)
                    : filter(Stream.concat(cams, cameras.groups().stream().map(g -> "group:" + g)), cur);
            case "quad" -> pos == 1 ? filter(ids, cur) : filter(cams, cur);
            case "mode" -> pos == 1 ? filter(ids, cur)
                    : pos == 2 ? filter(Stream.of("live", "snapshot", "shader", "quad"), cur) : List.of();
            case "power" -> pos == 1 ? filter(ids, cur) : pos == 2 ? filter(Stream.of("on", "off"), cur) : List.of();
            case "remove", "info" -> pos == 1 ? filter(ids, cur) : List.of();
            default -> List.of();
        };
    }

    private static List<String> filter(Stream<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.filter(o -> o.toLowerCase(Locale.ROOT).startsWith(p)).sorted().toList();
    }

    private void msg(CommandSender s, String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... r) {
        messages.get().send(s, key, r);
    }
}

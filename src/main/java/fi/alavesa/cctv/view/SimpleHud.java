package fi.alavesa.cctv.view;

import fi.alavesa.cctv.camera.Camera;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

/**
 * Interim HUD: one subtitle line with the camera name and time, and the control help for the first seconds.
 * Uses title/subtitle, not the action bar (which other plugins on the server share).
 */
final class SimpleHud implements ViewHud {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Title.Times TIMES = Title.Times.times(Duration.ZERO, Duration.ofMillis(1600),
            Duration.ofMillis(600));

    private final int helpTicks;

    SimpleHud(int helpSeconds) {
        this.helpTicks = helpSeconds * 20;
    }

    @Override
    public void start(ViewSession s, Player p) {
        show(s, p);
    }

    @Override
    public void tick(ViewSession s, Player p) {
        if (s.age() % 20 == 0) show(s, p);
    }

    @Override
    public void switched(ViewSession s, Player p) {
        show(s, p);
    }

    @Override
    public void signalLost(ViewSession s, Player p) {
        p.showTitle(Title.title(Component.text("SIGNAL LOST", NamedTextColor.RED), Component.empty(), TIMES));
    }

    @Override
    public void stop(ViewSession s, Player p) {
        p.clearTitle();
    }

    private void show(ViewSession s, Player p) {
        if (s.ending()) return;
        Component line = s.age() < helpTicks ? help(s.camera()) : info(s);
        p.showTitle(Title.title(Component.empty(), line, TIMES));
    }

    private static Component info(ViewSession s) {
        Camera c = s.camera();
        boolean blink = (s.age() / 20) % 2 == 0;
        return Component.text()
                .append(Component.text(blink ? "● " : "  ", NamedTextColor.RED))
                .append(Component.text("REC  ", NamedTextColor.WHITE))
                .append(Component.text(c.id().toUpperCase() + "  ", NamedTextColor.GRAY))
                .append(Component.text(c.name() + "  ", NamedTextColor.WHITE))
                .append(Component.text("[" + c.group() + "]  ", NamedTextColor.DARK_GRAY))
                .append(Component.text(LocalTime.now().format(CLOCK), NamedTextColor.WHITE))
                .append(s.zoom() > 1.01f ? Component.text("  " + Math.round(s.zoom()) + "x", NamedTextColor.YELLOW)
                        : Component.empty())
                .build();
    }

    private static Component help(Camera c) {
        NamedTextColor k = NamedTextColor.YELLOW;
        NamedTextColor t = NamedTextColor.GRAY;
        var b = Component.text();
        if (c.type().ptz()) {
            b.append(Component.keybind("key.left", k)).append(Component.text("/", t))
                    .append(Component.keybind("key.right", k)).append(Component.text(" pan  ", t))
                    .append(Component.keybind("key.forward", k)).append(Component.text("/", t))
                    .append(Component.keybind("key.back", k)).append(Component.text(" tilt  ", t))
                    .append(Component.keybind("key.sprint", k)).append(Component.text("+", t))
                    .append(Component.keybind("key.left", k)).append(Component.text("/", t))
                    .append(Component.keybind("key.right", k)).append(Component.text(" camera  ", t));
        } else {
            b.append(Component.keybind("key.left", k)).append(Component.text("/", t))
                    .append(Component.keybind("key.right", k)).append(Component.text(" camera  ", t));
        }
        if (c.type().zoomLevels().size() > 1) {
            b.append(Component.keybind("key.jump", k)).append(Component.text(" zoom  ", t));
        }
        return b.append(Component.keybind("key.sneak", k)).append(Component.text(" exit", t)).build();
    }
}

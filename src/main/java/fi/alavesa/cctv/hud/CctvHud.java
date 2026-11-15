package fi.alavesa.cctv.hud;

import static fi.alavesa.cctv.hud.HudText.ABERRATION;
import static fi.alavesa.cctv.hud.HudText.AMBER;
import static fi.alavesa.cctv.hud.HudText.BLACK;
import static fi.alavesa.cctv.hud.HudText.BLINK;
import static fi.alavesa.cctv.hud.HudText.DARK;
import static fi.alavesa.cctv.hud.HudText.FLICKER;
import static fi.alavesa.cctv.hud.HudText.GLITCH;
import static fi.alavesa.cctv.hud.HudText.GREY;
import static fi.alavesa.cctv.hud.HudText.RED;
import static fi.alavesa.cctv.hud.HudText.SCANLINES;
import static fi.alavesa.cctv.hud.HudText.WHITE;

import fi.alavesa.cctv.api.CameraState;
import fi.alavesa.cctv.camera.Camera;
import fi.alavesa.cctv.camera.CameraManager;
import fi.alavesa.cctv.config.CctvConfig;
import fi.alavesa.cctv.view.ViewHud;
import fi.alavesa.cctv.view.ViewSession;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

/**
 * The CCTV on-screen display, drawn with the cctv:hud fonts in the title's subtitle line (see {@link HudText}).
 *
 * <p>Layout, in font px from the screen centre (1 font px = 2 GUI px); the box is hud.half-width wide each way:
 * <pre>
 *  ● REC                              2026-11-14
 *  CAM-4                                13:17:23
 *  YARD PTZ                          ▶ CAM-4 YARD
 *                                      CAM-5 GATE
 *                    ...                 ...
 *            A/D PAN  W/S TILT  SHIFT EXIT          (first seconds)
 *  NET TEST                                   2X
 *  SIG ▂▄▆█
 * </pre>
 * Corner positions are relative to the centre, so on very narrow or very large-GUI-scale screens the box can
 * clip; tune hud.half-width. Brackets and vignette come from the post effect, which knows the real screen size.
 */
public final class CctvHud implements ViewHud {

    public enum Card {
        NO_SIGNAL("NO SIGNAL"), CAMERA_OFFLINE("CAMERA OFFLINE"), POWER_FAILURE("POWER FAILURE"),
        ACCESS_DENIED("ACCESS DENIED");

        final String text;

        Card(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    private static final Title.Times LIVE = Title.Times.times(Duration.ZERO, Duration.ofMillis(1600), Duration.ZERO);
    private static final Title.Times CARD = Title.Times.times(Duration.ZERO, Duration.ofMillis(1800),
            Duration.ofMillis(300));
    private static final int TEXT_FX = FLICKER | GLITCH | ABERRATION | SCANLINES;
    private static final int LIST_SIZE = 6;

    private final CameraManager cameras;
    private CctvConfig cfg;
    private DateTimeFormatter date;
    private DateTimeFormatter time;
    /** network -> "ZONE CAM-ID" while that network has an active alert, else null */
    private java.util.function.Function<String, String> alerts = g -> null;

    public CctvHud(CameraManager cameras, CctvConfig cfg) {
        this.cameras = cameras;
        config(cfg);
    }

    public void setAlerts(java.util.function.Function<String, String> alerts) {
        this.alerts = alerts;
    }

    public void config(CctvConfig cfg) {
        this.cfg = cfg;
        this.date = DateTimeFormatter.ofPattern(cfg.hudDateFormat);
        this.time = DateTimeFormatter.ofPattern(cfg.hudTimeFormat);
    }

    @Override
    public void start(ViewSession s, Player p) {
        show(s, p);
    }

    @Override
    public void tick(ViewSession s, Player p) {
        if (s.age() % 20 == 0 || s.age() == cfg.helpSeconds * 20L) show(s, p);
    }

    @Override
    public void switched(ViewSession s, Player p) {
        show(s, p);
    }

    @Override
    public void signalLost(ViewSession s, Player p) {
        p.showTitle(Title.title(Component.empty(), card(Card.NO_SIGNAL, s.camera().id()), CARD));
    }

    @Override
    public void stop(ViewSession s, Player p) {
        p.clearTitle();
    }

    /** A full-screen state card outside or inside a session (refused view, lost camera). */
    public void card(Player p, Card card, String detail) {
        p.showTitle(Title.title(Component.empty(), card(card, detail), CARD));
    }

    private Component card(Card card, String detail) {
        float w = cfg.hudHalfWidth;
        int cells = card.text.length() + 2;
        return new HudText()
                .bigPanel(0, 1, cells, BLACK)
                .bigCenter(0, 1, card.text, card == Card.ACCESS_DENIED || card == Card.POWER_FAILURE ? RED : WHITE,
                        TEXT_FX)
                .center(0, 9, detail, GREY, FLICKER | SCANLINES)
                .left(-w, 0, "CCTV", GREY, FLICKER)
                .build();
    }

    private void show(ViewSession s, Player p) {
        // a state card (refused switch, ...) is on screen: don't overwrite it with the live overlay
        if (s.ending() || s.age() < s.hudHoldUntil()) return;
        p.showTitle(Title.title(Component.empty(), layout(s), LIVE));
    }

    private Component layout(ViewSession s) {
        Camera c = s.camera();
        float w = cfg.hudHalfWidth;
        LocalDateTime now = LocalDateTime.now();
        HudText t = new HudText();

        t.left(-w, 0, String.valueOf(HudText.REC_DOT), WHITE, TEXT_FX | BLINK);
        t.left(-w + HudText.ADVANCE + 2, 0, "REC", WHITE, TEXT_FX);
        t.right(w, 0, now.format(date), WHITE, TEXT_FX);
        t.left(-w, 1, c.id(), WHITE, TEXT_FX);
        t.right(w, 1, now.format(time), WHITE, TEXT_FX);
        t.left(-w, 2, trim(c.name(), 18), WHITE, TEXT_FX);
        String alert = alerts.apply(c.group());
        if (alert != null) t.left(-w, 3, "! ALERT " + alert, RED, TEXT_FX | BLINK);
        else if (c.state() == CameraState.DAMAGED) t.left(-w, 3, "SIGNAL WEAK", AMBER, TEXT_FX | BLINK);

        cameraList(t, c, w);

        if (s.age() < cfg.helpSeconds * 20L) t.center(0, 9, s.controls() ? help(c) : "VIEW ONLY  SHIFT EXIT", GREY, SCANLINES);
        t.left(-w, 11, "NET " + trim(c.group(), 14), GREY, TEXT_FX);
        if (s.zoom() > 1.01f) t.right(w, 11, Math.round(s.zoom()) + "X", AMBER, TEXT_FX);
        t.left(-w, 12, "SIG", GREY, TEXT_FX);
        int bars = signalBars(c);
        for (int i = 0; i < 4; i++) {
            char g = (char) ((i < bars ? HudText.BAR_FULL_1 : HudText.BAR_EMPTY_1) + i);
            int pal = bars <= 1 ? RED : bars == 2 ? AMBER : WHITE;
            t.left(-w + 4 * HudText.ADVANCE + i * 3, 12, String.valueOf(g), i < bars ? pal : WHITE, FLICKER);
        }
        return t.build();
    }

    /** Up to LIST_SIZE cameras of the network around the current one; current highlighted, offline dimmed. */
    private void cameraList(HudText t, Camera current, float w) {
        List<Camera> net = new ArrayList<>();
        for (Camera c : cameras.cameras()) if (c.group().equals(current.group())) net.add(c);
        if (net.size() < 2) return;
        net.sort((a, b) -> CameraManager.naturalCompare(a.id(), b.id()));
        int i = net.indexOf(current);
        int from = Math.max(0, Math.min(i - LIST_SIZE / 2, net.size() - LIST_SIZE));
        int row = 3;
        for (int k = from; k < Math.min(net.size(), from + LIST_SIZE); k++, row++) {
            Camera c = net.get(k);
            boolean here = c == current;
            boolean up = c.state().viewable();
            boolean alert = c.alerting();
            String line = (here ? HudText.POINTER : ' ') + " " + trim(c.id(), 8) + (alert ? " !" : up ? "" : " X");
            int pal = alert ? RED : here ? AMBER : up ? GREY : DARK;
            // steady red: blinking would hide the whole list entry half the time; the ALERT line already blinks
            t.right(w, row, line, pal, here || alert ? TEXT_FX : FLICKER | SCANLINES);
        }
    }

    private static int signalBars(Camera c) {
        if (!c.state().viewable()) return 0;
        return (int) Math.ceil(Math.clamp(c.health() / c.maxHealth(), 0, 1) * 4);
    }

    private static String help(Camera c) {
        StringBuilder b = new StringBuilder();
        if (c.type().ptz()) b.append("A/D PAN  W/S TILT  CTRL+A/D CAM");
        else b.append("A/D CAMERA");
        if (c.type().zoomLevels().size() > 1) b.append("  SPACE ZOOM");
        return b.append("  SHIFT EXIT").toString();
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}

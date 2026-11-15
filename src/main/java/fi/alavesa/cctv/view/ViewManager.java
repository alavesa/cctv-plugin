package fi.alavesa.cctv.view;

import com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent;
import com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent;
import fi.alavesa.cctv.api.CameraView;
import fi.alavesa.cctv.api.event.CameraDamageEvent;
import fi.alavesa.cctv.api.event.CameraDisableEvent;
import fi.alavesa.cctv.api.event.CameraRepairEvent;
import fi.alavesa.cctv.api.event.CameraSwitchEvent;
import fi.alavesa.cctv.api.event.CameraViewEndEvent;
import fi.alavesa.cctv.api.event.CameraViewEndEvent.Reason;
import fi.alavesa.cctv.api.event.CameraViewStartEvent;
import fi.alavesa.cctv.camera.Camera;
import fi.alavesa.cctv.camera.CameraManager;
import fi.alavesa.cctv.camera.ViewBridge;
import fi.alavesa.cctv.config.CctvConfig;
import fi.alavesa.cctv.config.Messages;
import fi.alavesa.cctv.effect.PostEffectService;
import fi.alavesa.cctv.effect.PostEffects;
import fi.alavesa.cctv.hud.CctvHud;
import fi.alavesa.cctv.pack.PackService;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Spider;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * Full-screen camera view.
 *
 * <p>How it works (see docs/PHASE1-ARCHITECTURE.md §3): the player is put in spectator mode and spectates a hidden
 * anchor entity at the camera lens ({@code setSpectatorTarget}). While spectating, the server keeps the player's own
 * position on the anchor, so chunks around a camera anywhere in the world stream normally, and the spectator HUD
 * already hides hand, hotbar, hearts and crosshair. A Mannequin "body double" stays where the player sat; hurting it
 * pulls the player out. The look comes from {@link PostEffectService}; the overlay from {@link ViewHud}.
 *
 * <p>Controls come from {@link PlayerInputEvent} (the only inputs a spectating vanilla client sends): A/D pan,
 * W/S tilt, Ctrl+A/D (or A/D on fixed cameras) switch camera, Space zoom, Shift exit.
 */
public final class ViewManager implements Listener, ViewBridge {

    private final Plugin plugin;
    private final CameraManager cameras;
    private final PackService packs;
    private final Supplier<Messages> messages;
    private final NamespacedKey restoreKey;
    private final Map<UUID, ViewSession> sessions = new HashMap<>();
    private final Map<Long, Integer> bodyTickets = new HashMap<>();
    private CctvConfig cfg;
    private PostEffectService effects;
    private ViewHud plainHud;
    private final CctvHud cctvHud;
    private @Nullable BukkitTask task;

    public ViewManager(Plugin plugin, CameraManager cameras, PackService packs, PostEffectService effects,
                       Supplier<Messages> messages, CctvConfig cfg) {
        this.plugin = plugin;
        this.cameras = cameras;
        this.packs = packs;
        this.effects = effects;
        this.messages = messages;
        this.cfg = cfg;
        this.plainHud = new SimpleHud(cfg.helpSeconds);
        this.cctvHud = new CctvHud(cameras, cfg);
        this.restoreKey = new NamespacedKey(plugin, "restore");
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
        // FancyNpcs may fire its interact event off the main thread; hop back before touching sessions
        NpcBody.bind(plugin, (attacker, npc) -> Bukkit.getScheduler().runTask(plugin, () -> npcHit(attacker, npc)));
        if (effects.name().equals("mob")) FakeAnchor.bind(plugin.getLogger());
        // players online during a plugin (re)load may carry a restore record from a crash
        for (Player p : Bukkit.getOnlinePlayers()) restoreFromRecord(p);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        for (ViewSession s : List.copyOf(sessions.values())) finish(s, Reason.SHUTDOWN);
    }

    public void reload(CctvConfig cfg, PostEffectService effects) {
        this.cfg = cfg;
        if (!effects.name().equals(this.effects.name())) {
            for (ViewSession s : sessions.values()) {
                Player p = Bukkit.getPlayer(s.player);
                if (p != null) this.effects.removeOurs(p);
                s.applied = List.of();
                s.effectsDirty = true;
            }
        }
        this.effects = effects;
        this.plainHud = new SimpleHud(cfg.helpSeconds);
        this.cctvHud.config(cfg);
    }

    public PostEffectService effects() {
        return effects;
    }

    public void setAlertSource(java.util.function.Function<String, String> alerts) {
        cctvHud.setAlerts(alerts);
    }

    /** Glyph HUD when the client has the CCTV pack (its fonts), otherwise the plain subtitle. */
    private ViewHud hud(ViewSession s) {
        return s.packHud ? cctvHud : plainHud;
    }

    /** Refused view: a full-screen card if the client has the pack fonts, plus the chat message. */
    private void refuse(Player p, Camera c, boolean access) {
        CctvHud.Card card = access ? CctvHud.Card.ACCESS_DENIED
                : c.state() == fi.alavesa.cctv.api.CameraState.UNPOWERED ? CctvHud.Card.POWER_FAILURE
                : CctvHud.Card.CAMERA_OFFLINE;
        if (packs.loaded(p)) {
            cctvHud.card(p, card, c.id());
            ViewSession s = sessions.get(p.getUniqueId());
            if (s != null) s.hudHoldUntil = s.age + 40;
        }
        if (access) msg(p, "view-denied", Messages.p("id", c.id()), Messages.p("clearance", c.clearance()));
        else msg(p, "view-offline", Messages.p("id", c.id()), Messages.p("state", card.text()));
    }

    // ------------------------------------------------------------------ API (ViewBridge)

    @Override
    public boolean start(Player p, Camera c) {
        return start(p, c, false);
    }

    /**
     * Only viewers who opened the camera at a security terminal (or have cctv.control.anywhere, ops by default) may
     * turn PTZ cameras, zoom and switch; everyone else watches and can only leave.
     */
    @Override
    public boolean start(Player p, Camera c, boolean controls) {
        boolean control = controls || p.hasPermission("cctv.control.anywhere");
        ViewSession existing = sessions.get(p.getUniqueId());
        if (existing != null) {
            if (existing.ending()) return false;
            existing.controls = control;
            return switchTo(existing, p, c, true);
        }
        if (!c.state().viewable()) {
            refuse(p, c, false);
            return false;
        }
        if (!cameras.canAccess(p, c)) {
            refuse(p, c, true);
            return false;
        }
        if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR) {
            msg(p, "view-not-now");
            return false;
        }
        World w = c.bukkitWorld();
        if (w == null) {
            msg(p, "world-not-loaded", Messages.p("world", c.worldName()));
            return false;
        }
        if (!new CameraViewStartEvent(p, c).callEvent()) return false;

        p.closeInventory();
        p.leaveVehicle();
        ViewSession s = new ViewSession(p.getUniqueId(), c, p.getLocation(), p.getGameMode(), p.getAllowFlight(),
                p.isFlying());
        s.controls = control;
        writeRecord(p, s);
        sessions.put(p.getUniqueId(), s);
        s.fullEffects = !cfg.requirePackForEffects || packs.loaded(p);
        s.packHud = packs.loaded(p);
        if (effects.appliesEffects() && !s.fullEffects) msg(p, "view-no-pack");

        if (cfg.bodyDouble) spawnBody(s, p);
        s.anchor = spawnAnchor(s, c, p);
        s.internal = true;
        try {
            p.setGameMode(GameMode.SPECTATOR);
            p.teleport(anchorEye(c));
        } finally {
            s.internal = false;
        }
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o != p && o.getGameMode() == GameMode.SPECTATOR) o.hidePlayer(plugin, p);
        }
        s.attachIn = cfg.attachDelayTicks;
        transition(s, cfg.transitionTicks);
        sound(p, cfg.switchSound);
        hud(s).start(s, p);
        return true;
    }

    @Override
    public boolean stop(Player p) {
        ViewSession s = sessions.get(p.getUniqueId());
        if (s == null) return false;
        end(s, Reason.API, cfg.exitTicks);
        return true;
    }

    @Override
    public Optional<CameraView> viewing(Player p) {
        ViewSession s = sessions.get(p.getUniqueId());
        return s == null ? Optional.empty() : Optional.of(s.camera);
    }

    public @Nullable ViewSession session(Player p) {
        return sessions.get(p.getUniqueId());
    }

    public int count() {
        return sessions.size();
    }

    private long sweepClock;
    private final java.util.Set<String> peacefulWarned = new java.util.HashSet<>();

    /**
     * Every 10 s: body doubles no viewer owns are removed (Mannequins tagged as bodies, FancyNpcs NPCs with our
     * prefix). They can be left by a crash, a plugin reload or a removal that raced with FancyNpcs' own tasks.
     */
    private void sweepStrayBodies() {
        java.util.Set<UUID> bodies = new java.util.HashSet<>();
        java.util.Set<String> npcs = new java.util.HashSet<>();
        for (ViewSession s : sessions.values()) {
            if (s.bodyId != null) bodies.add(s.bodyId);
            if (s.npcBody != null) npcs.add(s.npcBody);
        }
        for (World w : Bukkit.getWorlds()) {
            for (Mannequin m : w.getEntitiesByClass(Mannequin.class)) {
                if ("body".equals(m.getPersistentDataContainer().get(cameras.keys().part, PersistentDataType.STRING))
                        && !bodies.contains(m.getUniqueId())) {
                    m.remove();
                }
            }
        }
        NpcBody.purgeOrphans(npcs, plugin.getLogger());
    }

    /** Viewers whose body double is a FancyNpcs NPC, with where it stands (the monitor feed draws them). */
    public List<Map.Entry<Player, Location>> npcBodies() {
        List<Map.Entry<Player, Location>> out = new ArrayList<>();
        for (ViewSession s : sessions.values()) {
            if (s.npcBody == null) continue;
            Player p = Bukkit.getPlayer(s.player);
            if (p != null) out.add(Map.entry(p, s.origin));
        }
        return out;
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        if (++sweepClock % 200 == 0) sweepStrayBodies();
        if (sessions.isEmpty()) return;
        for (ViewSession s : List.copyOf(sessions.values())) {
            Player p = Bukkit.getPlayer(s.player);
            if (p == null) {
                finish(s, Reason.QUIT);
                continue;
            }
            s.age++;
            if (s.ending()) {
                if (s.effectsDirty) applyEffects(s, p);
                if (--s.endIn <= 0) finish(s, s.endReason == null ? Reason.API : s.endReason);
                continue;
            }
            Camera c = s.camera;
            if (cameras.get(c.id()) != c || !c.state().viewable()) {
                cameraLost(s, p);
                continue;
            }
            if (s.anchor == null || !s.anchor.isValid()) {
                if (s.fake != null) s.fake.remove(p);
                s.fake = null;
                s.anchor = spawnAnchor(s, c, p);
                s.attachIn = cfg.attachDelayTicks;
            }
            if (s.attachIn > 0 && --s.attachIn == 0) attach(s, p);
            else if (s.attachIn == 0 && p.getSpectatorTarget() != s.anchor) attach(s, p);

            if (s.transientTicks > 0 && --s.transientTicks == 0) s.effectsDirty = true;
            if (s.attachIn == 0) steer(s, c);
            followCamera(s, c, p);
            if (s.effectsDirty) applyEffects(s, p);
            hud(s).tick(s, p);
        }
    }

    /** Held A/D/W/S move the PTZ target a little ahead of the motor; releasing stops it where it is. */
    private void steer(ViewSession s, Camera c) {
        Input in = s.input;
        if (!c.type().ptz() || in == null || !s.controls) return;
        int dx = in.isSprint() ? 0 : (in.isRight() ? 1 : 0) - (in.isLeft() ? 1 : 0);
        int dy = (in.isBackward() ? 1 : 0) - (in.isForward() ? 1 : 0);
        if (dx != 0 || dy != 0) {
            float ahead = c.type().panSpeed() * cfg.ptzLookahead;
            cameras.aimRaw(c, c.pan() + dx * ahead, c.tilt() + dy * ahead);
            s.ptzHeld = true;
        } else if (s.ptzHeld) {
            s.ptzHeld = false;
            cameras.aimRaw(c, c.pan(), c.tilt());
        }
    }

    private void followCamera(ViewSession s, Camera c, Player p) {
        Entity a = s.anchor;
        if (a == null) return;
        if (s.fake != null && s.attachIn == 0) s.fake.move(p, lensPose(s, c));
        float yaw = c.yaw(), pitch = c.pitch();
        Location want = anchorAt(c, s.zoom());
        if (a instanceof LivingEntity le) want.subtract(0, le.getEyeHeight(), 0);
        if (a.getLocation().distanceSquared(want) > 1e-4) {
            a.teleport(want); // also carries the rotation
            if (a instanceof LivingEntity le) le.setBodyYaw(yaw);
            return;
        }
        if (Math.abs(a.getYaw() - yaw) > 0.01f || Math.abs(a.getPitch() - pitch) > 0.01f) {
            a.setRotation(yaw, pitch);
            if (a instanceof LivingEntity le) le.setBodyYaw(yaw);
        }
    }

    /**
     * Where the anchor sits for a zoom level. "dolly" zoom moves the view forward along the line of sight
     * (zoom-1) x view.zoom.blocks-per-level blocks, stopping 0.6 blocks short of the first solid block. It works on
     * every version (26.2 can't receive post effects) and keeps full resolution, unlike a crop. Mob anchors are
     * lowered by their eye height.
     */
    private Location anchorAt(Camera c, float zoom) {
        Location eye = c.location();
        if (zoom > 1.01f && !cfg.zoomMode.equals("digital")) {
            // what is in the centre of the view should look `zoom` times bigger: move (1 - 1/zoom) of the way to
            // it, at most blocks-per-level per level, and never closer than 0.6 blocks to a surface
            org.bukkit.util.Vector dir = eye.getDirection();
            double cap = (zoom - 1) * cfg.zoomBlocksPerLevel;
            org.bukkit.util.RayTraceResult hit = eye.getWorld().rayTraceBlocks(eye, dir, 64,
                    org.bukkit.FluidCollisionMode.NEVER, true);
            double target = hit == null ? 64 : hit.getHitPosition().distance(eye.toVector());
            double dist = Math.max(0, Math.min(Math.min(cap, target * (1 - 1 / zoom)), target - 0.6));
            eye.add(dir.multiply(dist));
        }
        return eye;
    }

    private void attach(ViewSession s, Player p) {
        if (s.anchor == null) return;
        // the view sits inside the lens; without this the player looks at the inside of the camera housing
        for (Entity part : cameras.modelEntities(s.camera)) p.hideEntity(plugin, part);
        s.internal = true;
        try {
            p.setSpectatorTarget(s.anchor);
        } finally {
            s.internal = false;
        }
        // 26.2 look: this client spectates its own fake mob; the server keeps following the display anchor
        if (s.fake != null) s.fake.show(p, lensPose(s, s.camera));
    }

    /** The lens position for the current zoom, with the camera's rotation. */
    private Location lensPose(ViewSession s, Camera c) {
        Location l = anchorAt(c, s.zoom());
        l.setYaw(c.yaw());
        l.setPitch(c.pitch());
        return l;
    }

    // ------------------------------------------------------------------ controls

    @EventHandler
    public void onInput(PlayerInputEvent e) {
        ViewSession s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null || s.ending()) return;
        Input before = s.input;
        Input now = e.getInput();
        s.input = now;
        if (rising(before, now, Input::isSneak)) {
            end(s, Reason.EXIT, cfg.exitTicks);
            return;
        }
        if (s.attachIn > 0 || !s.controls) return;
        Camera c = s.camera;
        if (rising(before, now, Input::isJump) && c.type().zoomLevels().size() > 1) {
            setZoomIndex(s, e.getPlayer(), (s.zoomIndex + 1) % c.type().zoomLevels().size());
        }
        int dir = rising(before, now, Input::isLeft) ? -1 : rising(before, now, Input::isRight) ? 1 : 0;
        if (dir != 0 && (now.isSprint() || !c.type().ptz())) switchBy(s, e.getPlayer(), dir);
    }

    private void setZoomIndex(ViewSession s, Player p, int index) {
        s.zoomIndex = Math.clamp(index, 0, s.camera.type().zoomLevels().size() - 1);
        s.effectsDirty = true;
        hud(s).switched(s, p);
        followCamera(s, s.camera, p);
    }

    /** Sets a viewer's zoom step (0 = 1x) on their current camera. @return false if they aren't viewing */
    public boolean zoom(Player p, int index) {
        ViewSession s = sessions.get(p.getUniqueId());
        if (s == null || s.ending()) return false;
        setZoomIndex(s, p, index);
        return true;
    }

    private static boolean rising(@Nullable Input before, Input now, java.util.function.Predicate<Input> key) {
        return key.test(now) && (before == null || !key.test(before));
    }

    private void switchBy(ViewSession s, Player p, int dir) {
        Camera from = s.camera;
        Camera c = from;
        int guard = cameras.cameras().size();
        while (guard-- > 0) {
            c = cameras.neighbour(c, dir, cfg.switchSkipsOffline);
            if (c == null || c == from) break;
            if (cameras.canAccess(p, c)) {
                switchTo(s, p, c, false);
                return;
            }
        }
        msg(p, "view-no-other");
    }

    private boolean switchTo(ViewSession s, Player p, Camera to, boolean explicit) {
        if (to == s.camera) return true;
        if (!to.state().viewable()) {
            if (explicit) refuse(p, to, false);
            return false;
        }
        if (!cameras.canAccess(p, to)) {
            if (explicit) refuse(p, to, true);
            return false;
        }
        if (to.bukkitWorld() == null || !new CameraSwitchEvent(p, s.camera, to).callEvent()) return false;
        Entity old = s.anchor;
        FakeAnchor oldFake = s.fake;
        s.fake = null;
        for (Entity part : cameras.modelEntities(s.camera)) p.showEntity(plugin, part);
        s.camera = to;
        s.zoomIndex = 0;
        s.ptzHeld = false;
        s.anchor = spawnAnchor(s, to, p);
        s.internal = true;
        try {
            p.setSpectatorTarget(null);
            p.teleport(anchorEye(to));
        } finally {
            s.internal = false;
        }
        if (oldFake != null) oldFake.remove(p);
        if (old != null) old.remove();
        s.attachIn = cfg.attachDelayTicks;
        transition(s, cfg.transitionTicks);
        sound(p, cfg.switchSound);
        hud(s).switched(s, p);
        return true;
    }

    private void cameraLost(ViewSession s, Player p) {
        if (cfg.switchOnLost) {
            Camera from = s.camera;
            Camera c = from;
            int guard = cameras.cameras().size();
            while (guard-- > 0) {
                c = cameras.neighbour(c, 1, true);
                if (c == null || c == from) break;
                if (cameras.canAccess(p, c) && switchTo(s, p, c, false)) {
                    msg(p, "view-switched-lost", Messages.p("id", from.id()), Messages.p("to", c.id()));
                    return;
                }
            }
        }
        end(s, Reason.CAMERA_LOST, cfg.lostTicks);
    }

    // ------------------------------------------------------------------ ending

    /** Ends with a short transition (static, or "signal lost" for DAMAGED / CAMERA_LOST), then restores. */
    void end(ViewSession s, Reason reason, int ticks) {
        if (s.ending()) return;
        Player p = Bukkit.getPlayer(s.player);
        if (p == null || ticks <= 0) {
            finish(s, reason);
            return;
        }
        s.endIn = ticks;
        s.endReason = reason;
        s.effectsDirty = true;
        if (reason == Reason.DAMAGED || reason == Reason.CAMERA_LOST) {
            hud(s).signalLost(s, p);
            sound(p, cfg.lostSound);
        } else {
            s.transientTicks = ticks;
        }
    }

    /** Restores the player immediately. Safe to call more than once. */
    private void finish(ViewSession s, Reason reason) {
        if (sessions.remove(s.player) == null) return;
        Player p = Bukkit.getPlayer(s.player);
        Location back = s.body != null && s.body.isValid() ? s.body.getLocation() : s.origin;
        if (p != null) {
            s.internal = true;
            try {
                if (p.getGameMode() == GameMode.SPECTATOR) p.setSpectatorTarget(null);
                if (s.fake != null) s.fake.remove(p);
                if (s.restoreMode) p.setGameMode(s.mode);
                p.setAllowFlight(s.allowFlight);
                p.setFlying(s.allowFlight && s.flying);
                if (back.getWorld() != null) p.teleport(back);
            } finally {
                s.internal = false;
            }
            effects.removeOurs(p);
            for (Entity part : cameras.modelEntities(s.camera)) p.showEntity(plugin, part);
            hud(s).stop(s, p);
            for (Player o : Bukkit.getOnlinePlayers()) if (o != p) o.showPlayer(plugin, p);
            p.getPersistentDataContainer().remove(restoreKey);
        }
        if (s.anchor != null) s.anchor.remove();
        removeBody(s);
        if (p != null) {
            new CameraViewEndEvent(p, s.camera, reason).callEvent();
            if (reason == Reason.DAMAGED && cfg.forwardDamage && s.pendingDamage > 0) {
                double amount = s.pendingDamage;
                var source = s.pendingSource;
                // next tick: the game mode change must have landed or the hit is ignored
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!p.isOnline() || p.isDead()) return;
                    if (source != null) p.damage(amount, source);
                    else p.damage(amount);
                });
            }
        }
    }

    // ------------------------------------------------------------------ effects

    private void transition(ViewSession s, int ticks) {
        s.transientTicks = Math.max(s.transientTicks, ticks);
        s.effectsDirty = true;
    }

    private void applyEffects(ViewSession s, Player p) {
        s.effectsDirty = false;
        if (!s.fullEffects || !effects.appliesEffects()) return;
        List<Key> list = new ArrayList<>(4);
        if (s.zoom() > 1.01f && !cfg.zoomMode.equals("dolly")) list.add(PostEffects.zoom(s.zoom()));
        list.add(PostEffects.look(s.camera.type().look()));
        if (s.ending() && (s.endReason == Reason.DAMAGED || s.endReason == Reason.CAMERA_LOST)) {
            list.add(PostEffects.SIGNAL_LOST);
        } else if (s.transientTicks > 0) {
            list.add(PostEffects.STATIC_BURST);
        } else {
            double f = s.camera.health() / s.camera.maxHealth();
            if (f < cfg.criticalSignalBelow) list.add(PostEffects.SIGNAL_CRITICAL);
            else if (f < cfg.weakSignalBelow) list.add(PostEffects.SIGNAL_WEAK);
        }
        if (list.equals(s.applied)) return;
        s.applied = list;
        effects.apply(p, list);
    }

    // ------------------------------------------------------------------ anchor & body

    private Location anchorEye(Camera c) {
        return anchorAt(c, 1f);
    }

    /**
     * Item display by default (no model, so nothing renders). With effects.backend: mob, a creeper / spider /
     * enderman per the type's fallback-anchor, so the vanilla spectated-entity shader supplies the look.
     */
    private Entity spawnAnchor(ViewSession s, Camera c, Player viewer) {
        Location eye = anchorEye(c);
        World w = eye.getWorld();
        Class<? extends LivingEntity> mob = effects.name().equals("mob")
                ? mobClass(c.type().fallbackAnchor(), c.type().look()) : null;
        if (mob != null && FakeAnchor.available()) {
            // client-side mob for the look (works on peaceful and in no-mob regions); a display anchor for the server
            s.fake = FakeAnchor.of(mob, w, plugin.getLogger());
            if (s.fake != null) mob = null;
        }
        if (mob != null && w.getDifficulty() == org.bukkit.Difficulty.PEACEFUL
                && org.bukkit.entity.Monster.class.isAssignableFrom(mob)) {
            // peaceful refuses to spawn monsters (and removes them every tick): view without the mob-shader look
            if (peacefulWarned.add(w.getName())) {
                plugin.getLogger().warning("World " + w.getName() + " is on peaceful difficulty: the 26.2 camera "
                        + "looks need a creeper/spider/enderman anchor, which peaceful doesn't allow. Cameras there "
                        + "show without the look (use difficulty easy or higher, or a 26.3 server).");
            }
            mob = null;
        }
        Entity e;
        if (mob != null) {
            LivingEntity le = w.spawn(eye, mob, m -> {
                m.setAI(false);
                m.setInvisible(true);
                m.setSilent(true);
                m.setInvulnerable(true);
                m.setCollidable(false);
                m.setGravity(false);
                m.setRemoveWhenFarAway(false);
                anchorCommon(m);
            });
            if (le.isValid()) {
                // the camera sits at the entity's eyes, so lower the mob by its eye height
                le.teleport(eye.clone().subtract(0, le.getEyeHeight(), 0));
                e = le;
            } else {
                // a region/anti-mob plugin cancelled the spawn: view without the mob-shader look
                if (peacefulWarned.add("cancel:" + w.getName())) {
                    plugin.getLogger().warning("A plugin cancelled the " + mob.getSimpleName() + " camera anchor in "
                            + w.getName() + " (mob spawning denied?); cameras there show without the 26.2 look.");
                }
                e = null;
            }
        } else {
            e = null;
        }
        if (e == null) {
            e = w.spawn(eye, ItemDisplay.class, d -> {
                d.setTeleportDuration(2);
                d.setViewRange(0.01f);
                anchorCommon(d);
            });
        }
        viewer.showEntity(plugin, e);
        return e;
    }

    private void anchorCommon(Entity e) {
        e.setPersistent(false);
        e.setVisibleByDefault(false);
        e.setSilent(true);
        e.getPersistentDataContainer().set(cameras.keys().part, PersistentDataType.STRING, "anchor");
    }

    /**
     * The anchor mob for the vanilla spectated-entity shaders. "auto" picks by the camera's look; the CCTV pack
     * replaces those vanilla effects with its own: creeper = standard CCTV look, spider = night vision,
     * enderman ("invert") = thermal.
     */
    private static @Nullable Class<? extends LivingEntity> mobClass(String name, String look) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.equals("auto")) {
            n = switch (look) {
                case "night_vision" -> "spider";
                case "thermal" -> "enderman";
                default -> "creeper";
            };
        }
        return switch (n) {
            case "creeper" -> Creeper.class;
            case "spider" -> Spider.class;
            case "enderman" -> Enderman.class;
            default -> null;
        };
    }

    private void spawnBody(ViewSession s, Player p) {
        Location at = p.getLocation();
        if (!cfg.bodyMode.equals("mannequin") && NpcBody.available()) {
            s.npcBody = NpcBody.spawn(p, at, plugin.getLogger());
            if (s.npcBody != null) {
                ticket(s, at);
                return;
            }
        } else if (cfg.bodyMode.equals("fancynpcs")) {
            plugin.getLogger().warning("view.body is fancynpcs but FancyNpcs isn't available; using a Mannequin");
        }
        Mannequin body = at.getWorld().spawn(at, Mannequin.class, m -> {
            m.setPersistent(false);
            m.setProfile(ResolvableProfile.resolvableProfile(p.getPlayerProfile()));
            m.setImmovable(true);
            m.customName(p.name());
            m.setCustomNameVisible(true);
            m.setDescription(Component.empty());
            m.setMainHand(p.getMainHand());
            EntityEquipment from = p.getEquipment();
            EntityEquipment to = m.getEquipment();
            to.setHelmet(from.getHelmet());
            to.setChestplate(from.getChestplate());
            to.setLeggings(from.getLeggings());
            to.setBoots(from.getBoots());
            to.setItemInMainHand(from.getItemInMainHand());
            to.setItemInOffHand(from.getItemInOffHand());
            m.getPersistentDataContainer().set(cameras.keys().part, PersistentDataType.STRING, "body");
        });
        s.body = body;
        s.bodyId = body.getUniqueId();
        ticket(s, at);
    }

    private void ticket(ViewSession s, Location at) {
        Chunk ch = at.getChunk();
        s.bodyWorld = at.getWorld().getName();
        s.bodyChunk = ch.getChunkKey();
        // keep the body (and the spot the player returns to) loaded while they are away
        if (bodyTickets.merge(ticketKey(s), 1, Integer::sum) == 1) ch.addPluginChunkTicket(plugin);
    }

    private void removeBody(ViewSession s) {
        if (s.body != null) s.body.remove();
        Entity byId = s.bodyId == null ? null : Bukkit.getEntity(s.bodyId);
        if (byId != null) byId.remove();
        s.body = null;
        s.bodyId = null;
        NpcBody.remove(s.npcBody, plugin.getLogger());
        s.npcBody = null;
        if (s.bodyWorld == null) return;
        long key = ticketKey(s);
        int left = bodyTickets.merge(key, -1, Integer::sum);
        if (left <= 0) {
            bodyTickets.remove(key);
            World w = Bukkit.getWorld(s.bodyWorld);
            if (w != null) {
                int x = (int) s.bodyChunk, z = (int) (s.bodyChunk >> 32);
                w.removePluginChunkTicket(x, z, plugin);
            }
        }
        s.bodyWorld = null;
    }

    private static long ticketKey(ViewSession s) {
        return s.bodyChunk * 31 + (s.bodyWorld == null ? 0 : s.bodyWorld.hashCode());
    }

    // ------------------------------------------------------------------ crash-safe restore record

    private void writeRecord(Player p, ViewSession s) {
        Location o = s.origin;
        String rec = String.join("|", o.getWorld().getName(), Double.toString(o.getX()), Double.toString(o.getY()),
                Double.toString(o.getZ()), Float.toString(o.getYaw()), Float.toString(o.getPitch()), s.mode.name(),
                Boolean.toString(s.allowFlight), Boolean.toString(s.flying));
        p.getPersistentDataContainer().set(restoreKey, PersistentDataType.STRING, rec);
    }

    /** After a crash: the player's data still says they were viewing. Put them back. */
    private void restoreFromRecord(Player p) {
        String rec = p.getPersistentDataContainer().get(restoreKey, PersistentDataType.STRING);
        effects.removeOurs(p);
        if (rec == null || sessions.containsKey(p.getUniqueId())) return;
        p.getPersistentDataContainer().remove(restoreKey);
        String[] f = rec.split("\\|");
        try {
            World w = Bukkit.getWorld(f[0]);
            if (p.getGameMode() == GameMode.SPECTATOR) p.setSpectatorTarget(null);
            p.setGameMode(GameMode.valueOf(f[6]));
            p.setAllowFlight(Boolean.parseBoolean(f[7]));
            p.setFlying(Boolean.parseBoolean(f[7]) && Boolean.parseBoolean(f[8]));
            if (w != null) {
                p.teleport(new Location(w, Double.parseDouble(f[1]), Double.parseDouble(f[2]), Double.parseDouble(f[3]),
                        Float.parseFloat(f[4]), Float.parseFloat(f[5])));
            }
            msg(p, "view-restored");
        } catch (RuntimeException e) {
            plugin.getLogger().warning("bad CCTV restore record for " + p.getName() + ": " + rec);
        }
    }

    // ------------------------------------------------------------------ listeners: player lifecycle

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        restoreFromRecord(p);
        if (p.getGameMode() == GameMode.SPECTATOR) hideViewersFrom(p);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        ViewSession s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) finish(s, Reason.QUIT);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        ViewSession s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) finish(s, Reason.DEATH);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTeleport(PlayerTeleportEvent e) {
        ViewSession s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null || s.internal) return;
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE) {
            // the spectator hotbar menu (number keys) would fly the player off to someone else
            e.setCancelled(true);
            return;
        }
        // another plugin moves the player: restore first, then let that teleport go ahead
        finish(s, Reason.TELEPORT);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        Player p = e.getPlayer();
        ViewSession s = sessions.get(p.getUniqueId());
        if (s != null && !s.internal) {
            // someone else changed the mode: keep theirs, end the session next tick
            s.restoreMode = false;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (sessions.get(p.getUniqueId()) == s) finish(s, Reason.GAMEMODE);
            });
        } else if (s == null && e.getNewGameMode() == GameMode.SPECTATOR) {
            hideViewersFrom(p);
        }
    }

    private void hideViewersFrom(Player spectator) {
        for (ViewSession s : sessions.values()) {
            Player v = Bukkit.getPlayer(s.player);
            if (v != null && v != spectator) spectator.hidePlayer(plugin, v);
        }
    }

    @EventHandler
    public void onStopSpectating(PlayerStopSpectatingEntityEvent e) {
        ViewSession s = sessions.get(e.getPlayer().getUniqueId());
        // vanilla un-spectates on sneak; exit is handled from PlayerInputEvent, with a transition
        if (s != null && !s.internal && !s.ending()) e.setCancelled(true);
    }

    @EventHandler
    public void onStartSpectating(PlayerStartSpectatingEntityEvent e) {
        ViewSession s = sessions.get(e.getPlayer().getUniqueId());
        // spectators can click an entity to jump into it; only our anchor is allowed
        if (s != null && !s.internal) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ listeners: body, anchor, cameras

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        Entity hurt = e.getEntity();
        String part = hurt.getPersistentDataContainer().get(cameras.keys().part, PersistentDataType.STRING);
        if (part == null) return;
        if (part.equals("anchor")) {
            e.setCancelled(true);
            return;
        }
        if (!part.equals("body")) return;
        e.setCancelled(true);
        for (ViewSession s : sessions.values()) {
            if (s.bodyId != null && s.bodyId.equals(hurt.getUniqueId()) && !s.ending()) {
                s.pendingDamage = e.getDamage();
                s.pendingSource = e.getDamageSource();
                end(s, Reason.DAMAGED, cfg.lostTicks);
                return;
            }
        }
    }

    /** A FancyNpcs double was hit: same as hurting a Mannequin double. */
    private void npcHit(Player attacker, String npc) {
        for (ViewSession s : sessions.values()) {
            if (npc.equals(s.npcBody) && !s.ending()) {
                var attr = attacker.getAttribute(org.bukkit.attribute.Attribute.ATTACK_DAMAGE);
                s.pendingDamage = attr == null ? 1.0 : Math.max(1.0, attr.getValue());
                s.pendingSource = org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.PLAYER_ATTACK)
                        .withCausingEntity(attacker).withDirectEntity(attacker).build();
                end(s, Reason.DAMAGED, cfg.lostTicks);
                return;
            }
        }
    }

    @EventHandler
    public void onCameraDisable(CameraDisableEvent e) {
        for (ViewSession s : List.copyOf(sessions.values())) {
            if (s.camera.id().equals(e.getCamera().id()) && !s.ending()) {
                Player p = Bukkit.getPlayer(s.player);
                if (p != null) cameraLost(s, p);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCameraDamage(CameraDamageEvent e) {
        dirtyFor(e.getCamera().id());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCameraRepair(CameraRepairEvent e) {
        dirtyFor(e.getCamera().id());
    }

    private void dirtyFor(String cameraId) {
        for (ViewSession s : sessions.values()) if (s.camera.id().equals(cameraId)) s.effectsDirty = true;
    }

    // ------------------------------------------------------------------ helpers

    private void sound(Player p, String key) {
        if (key == null || key.isBlank()) return;
        p.playSound(Sound.sound(Key.key(key), Sound.Source.MASTER, 1f, 1f), Sound.Emitter.self());
    }

    private void msg(Player p, String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... r) {
        messages.get().send(p, key, r);
    }
}

package fi.alavesa.cctv.view;

import fi.alavesa.cctv.api.event.CameraViewEndEvent;
import fi.alavesa.cctv.camera.Camera;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import org.bukkit.GameMode;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.jspecify.annotations.Nullable;

/** One player watching one camera. Mutable, main thread only, owned by {@link ViewManager}. */
public final class ViewSession {

    final UUID player;
    final Location origin;
    final GameMode mode;
    final boolean allowFlight;
    final boolean flying;
    Camera camera;
    @Nullable Entity anchor;
    @Nullable Entity body;
    /** FancyNpcs body double (NPC name), when that is used instead of a Mannequin */
    @Nullable String npcBody;
    long bodyChunk;
    @Nullable String bodyWorld;

    @Nullable Input input;
    boolean ptzHeld;
    int zoomIndex;

    /** ticks until setSpectatorTarget(anchor); the client must know the anchor entity before the camera packet */
    int attachIn;
    /** ticks left of the static-burst transition */
    int transientTicks;
    /** -1 while running; otherwise ticks until the session is finished */
    int endIn = -1;
    CameraViewEndEvent.@Nullable Reason endReason;
    boolean restoreMode = true;

    boolean fullEffects;
    /** the client has the CCTV pack: use the glyph HUD instead of the plain subtitle */
    boolean packHud;
    boolean effectsDirty = true;
    List<Key> applied = List.of();

    double pendingDamage;
    @Nullable DamageSource pendingSource;

    /** set while the manager itself changes game mode / spectator target / position, so its listeners let it pass */
    boolean internal;
    long age;
    /** session age until which the HUD must not be redrawn (a state card is showing) */
    long hudHoldUntil;

    ViewSession(UUID player, Camera camera, Location origin, GameMode mode, boolean allowFlight, boolean flying) {
        this.player = player;
        this.camera = camera;
        this.origin = origin;
        this.mode = mode;
        this.allowFlight = allowFlight;
        this.flying = flying;
    }

    public Camera camera() {
        return camera;
    }

    public UUID player() {
        return player;
    }

    public long age() {
        return age;
    }

    public long hudHoldUntil() {
        return hudHoldUntil;
    }

    public boolean ending() {
        return endIn >= 0;
    }

    public float zoom() {
        List<Float> z = camera.type().zoomLevels();
        return z.get(Math.min(zoomIndex, z.size() - 1));
    }

    public boolean fullEffects() {
        return fullEffects;
    }
}

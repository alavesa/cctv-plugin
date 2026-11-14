package fi.alavesa.cctv.camera;

import fi.alavesa.cctv.api.CameraState;
import fi.alavesa.cctv.api.CameraType;
import fi.alavesa.cctv.api.CameraView;
import fi.alavesa.cctv.api.Mount;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jspecify.annotations.Nullable;

/**
 * One camera record. The record is the source of truth; its entities (see {@link CameraParts}) are throwaway views
 * that are spawned on chunk load and removed on unload. Main thread only.
 */
public final class Camera implements CameraView {

    final String id;
    String name;
    String group;
    String typeKey;
    CameraType type;
    final String world;
    final double x;
    final double y;
    final double z;
    final Mount mount;
    float baseYaw;
    float basePitch;
    float pan;
    float tilt;
    float fov;
    float panMin;
    float panMax;
    float tiltMin;
    float tiltMax;
    double health;
    boolean powered = true;
    boolean enabled = true;
    int clearance;
    double damagedThreshold;

    // transient
    float targetPan;
    float targetTilt;
    boolean moving;
    /** server tick until which this camera is in alert, and the zone that triggered it */
    long alertUntil;
    @Nullable String alertZone;
    int motorTimer;
    @Nullable UUID baseEntity;
    @Nullable UUID headEntity;
    @Nullable UUID hitEntity;

    Camera(String id, String typeKey, CameraType type, String world, double x, double y, double z, Mount mount,
           float baseYaw, float basePitch) {
        this.id = id;
        this.typeKey = typeKey;
        this.type = type;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.mount = mount;
        this.baseYaw = baseYaw;
        this.basePitch = basePitch;
        this.name = id;
        this.group = "default";
        this.fov = type.fov();
        this.panMin = type.panMin();
        this.panMax = type.panMax();
        this.tiltMin = type.tiltMin();
        this.tiltMax = type.tiltMax();
        this.health = type.maxHealth();
        this.clearance = type.clearance();
    }

    @Override public String id() { return id; }
    @Override public String name() { return name; }
    @Override public String group() { return group; }
    @Override public CameraType type() { return type; }
    @Override public String worldName() { return world; }
    @Override public Mount mount() { return mount; }
    @Override public float baseYaw() { return baseYaw; }
    @Override public float basePitch() { return basePitch; }
    @Override public float pan() { return pan; }
    @Override public float tilt() { return tilt; }
    @Override public float fov() { return fov; }
    @Override public float panMin() { return panMin; }
    @Override public float panMax() { return panMax; }
    @Override public float tiltMin() { return tiltMin; }
    @Override public float tiltMax() { return tiltMax; }
    @Override public double health() { return health; }
    @Override public double maxHealth() { return type.maxHealth(); }
    @Override public boolean powered() { return powered; }
    @Override public boolean enabled() { return enabled; }
    @Override public int clearance() { return clearance; }

    /** The type key as stored; may differ from type().key() if the configured type was removed. */
    public String typeKey() { return typeKey; }

    @Override
    public float yaw() {
        return Orientation.wrap(baseYaw + pan);
    }

    @Override
    public float pitch() {
        return Math.clamp(basePitch + tilt, -90f, 90f);
    }

    @Override
    public Location location() {
        World w = Bukkit.getWorld(world);
        return new Location(w, x, y, z, yaw(), pitch());
    }

    public @Nullable World bukkitWorld() {
        return Bukkit.getWorld(world);
    }

    public int chunkX() {
        return (int) Math.floor(x) >> 4;
    }

    public int chunkZ() {
        return (int) Math.floor(z) >> 4;
    }

    @Override
    public CameraState state() {
        if (!enabled) return CameraState.DISABLED;
        if (health <= 0) return CameraState.DESTROYED;
        if (!powered) return CameraState.UNPOWERED;
        if (health < type.maxHealth() * damagedThreshold) return CameraState.DAMAGED;
        return CameraState.ONLINE;
    }

    /** True while an alert (a restricted zone sighting) is active on this camera. */
    public boolean alerting() {
        return alertZone != null && Bukkit.getCurrentTick() < alertUntil;
    }

    public @Nullable String alertZone() {
        return alerting() ? alertZone : null;
    }

    /** Puts the camera in alert for {@code ticks} (called by the alert service). */
    public void alert(String zone, int ticks) {
        alertZone = zone;
        alertUntil = Bukkit.getCurrentTick() + (long) ticks;
    }

    boolean spawned() {
        return headEntity != null;
    }
}

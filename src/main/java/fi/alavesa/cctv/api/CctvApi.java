package fi.alavesa.cctv.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Public CCTV API. Get it with {@code Bukkit.getServicesManager().load(CctvApi.class)}.
 * All methods must be called on the main thread.
 */
public interface CctvApi {

    Collection<? extends CameraView> cameras();

    Optional<CameraView> camera(String id);

    List<CameraView> camerasInGroup(String group);

    Set<String> groups();

    Map<String, CameraType> types();

    /**
     * Creates a camera. {@code eye} is the lens position. Fires CameraCreateEvent.
     *
     * @return the new camera, or empty if a listener cancelled the creation
     * @throws IllegalArgumentException for an unknown type or unloaded world
     */
    Optional<CameraView> create(String typeKey, Location eye, Mount mount, float baseYaw, float basePitch,
                                @Nullable String name, @Nullable String group);

    /** Removes a camera and its entities. Fires CameraRemoveEvent. */
    boolean remove(String id);

    boolean rename(String id, String name);

    boolean setGroup(String id, String group);

    /** Power on/off, e.g. from a power-grid plugin. Fires CameraDisableEvent/CameraEnableEvent on transitions. */
    boolean setPowered(String id, boolean powered);

    /** @return how many cameras changed */
    int setGroupPowered(String group, boolean powered);

    /** Admin enable/disable. */
    boolean setEnabled(String id, boolean enabled);

    /**
     * Damages a camera (fires the cancellable CameraDamageEvent first).
     *
     * @return the health after the hit, or -1 if the camera does not exist or the hit was cancelled
     */
    double damage(String id, double amount, @Nullable Entity source);

    /** @return the health after the repair, or -1 if the camera does not exist or the repair was cancelled */
    double repair(String id, double amount, @Nullable Player by);

    /** Sets the PTZ target (clamped to the camera's limits). The motor turns the model there smoothly. */
    boolean aim(String id, float pan, float tilt);

    ClearanceProvider clearanceProvider();

    void setClearanceProvider(ClearanceProvider provider);

    /** Clearance check plus the cctv.view permission. Does not check the camera state. */
    boolean canAccess(Player player, CameraView camera);

    /**
     * Attaches the player's view to a camera (or switches camera if they are already viewing).
     * Checks state and clearance and fires CameraViewStartEvent / CameraSwitchEvent.
     *
     * @return true if the player is now viewing that camera
     */
    boolean startViewing(Player player, String cameraId);

    /** Ends the player's viewing session. @return false if they weren't viewing */
    boolean stopViewing(Player player);

    /** True while the camera is in alert (it saw someone in a restricted zone of its network). */
    boolean alerting(String cameraId);

    /** The camera the player is currently viewing. */
    Optional<CameraView> viewing(Player player);
}

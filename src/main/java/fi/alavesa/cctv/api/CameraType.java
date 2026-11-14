package fi.alavesa.cctv.api;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.jspecify.annotations.Nullable;

/**
 * A camera type, defined entirely in config.yml (types section). Nothing here is hard-coded per type.
 *
 * @param key           config key, e.g. "dome"
 * @param displayName   shown in lists and on the placement item
 * @param headModel     item model for the part that turns with pan/tilt (the lens)
 * @param baseModel     item model for the fixed bracket, or null for single-part cameras
 * @param brokenModel   item model shown on the head while the camera is DESTROYED
 * @param placerModel   item model of the placement item
 * @param ptz           whether the camera can pan/tilt/zoom while viewed
 * @param fov           default field of view in degrees (used by the feed renderer and alerts)
 * @param panMin        default pan limit left of the base facing, degrees (negative)
 * @param panMax        default pan limit right of the base facing, degrees
 * @param tiltMin       default tilt limit, degrees (negative = up)
 * @param tiltMax       default tilt limit, degrees (positive = down)
 * @param panSpeed      PTZ motor speed, degrees per second
 * @param zoomLevels    digital zoom steps; [1] for no zoom
 * @param look          post-effect look while viewed: standard, night_vision, thermal
 * @param fallbackAnchor anchor mob for the vanilla mob-shader fallback: creeper, spider, enderman, none
 * @param maxHealth     hit points; 0 health = DESTROYED
 * @param clearance     default clearance level needed to view (0 = anyone)
 * @param modelScale    uniform display scale for both parts
 * @param bbmodel       a Blockbench file in plugins/CCTV/models/ that replaces the item models, or null
 */
public record CameraType(
        String key,
        Component displayName,
        NamespacedKey headModel,
        @Nullable NamespacedKey baseModel,
        NamespacedKey brokenModel,
        NamespacedKey placerModel,
        boolean ptz,
        float fov,
        float panMin,
        float panMax,
        float tiltMin,
        float tiltMax,
        float panSpeed,
        List<Float> zoomLevels,
        String look,
        String fallbackAnchor,
        double maxHealth,
        int clearance,
        float modelScale,
        @Nullable String bbmodel) {
}

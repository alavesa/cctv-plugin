package fi.alavesa.cctv.config;

import fi.alavesa.cctv.api.CameraType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.jspecify.annotations.Nullable;

/** Typed snapshot of config.yml. Rebuilt on every /cctv reload; never mutated. */
public final class CctvConfig {

    public final int autosaveSeconds;

    public final double placementOffset;
    public final float pitchWall;
    public final float pitchCeiling;
    public final float pitchFloor;
    public final float yawSnap;
    public final double minSpacing;

    public final boolean playersCanDamage;
    public final double meleeDamage;
    public final double projectileDamage;
    public final double explosionDamage;
    public final double explosionRadius;
    public final double damagedThreshold;

    public final Material repairMaterial;
    public final @Nullable NamespacedKey repairModel;
    public final double repairAmount;
    public final boolean repairConsumes;

    public final int ptzUpdateTicks;
    public final String motorSound;
    public final float motorVolume;
    public final float motorPitch;
    public final int motorSoundInterval;

    public final Material itemMaterial;
    public final float hitboxWidth;
    public final float hitboxHeight;

    public final int attachDelayTicks;
    public final int transitionTicks;
    public final int exitTicks;
    public final int lostTicks;
    public final boolean bodyDouble;
    /** auto | fancynpcs | mannequin | none */
    public final String bodyMode;
    public final boolean forwardDamage;
    public final boolean switchOnLost;
    public final boolean switchSkipsOffline;
    /** dolly (move the view forward, works everywhere) | digital (26.3 post effect crop) | both */
    public final String zoomMode;
    public final double zoomBlocksPerLevel;
    public final float ptzLookahead;
    public final int helpSeconds;
    public final boolean requirePackForEffects;
    public final String switchSound;
    public final String lostSound;

    public final float hudHalfWidth;
    public final String hudDateFormat;
    public final String hudTimeFormat;

    public final String effectsBackend;
    public final double weakSignalBelow;
    public final double criticalSignalBelow;

    public final String packDelivery;
    public final int packHttpPort;
    public final String packHttpBind;
    public final String packPublicHost;
    public final String packUrl;
    public final boolean packRequired;
    public final String packPrompt;
    public final boolean packTrustAny;
    public final String packSourceFolder;

    public final Map<String, CameraType> types;

    private CctvConfig(FileConfiguration c, Logger log) {
        autosaveSeconds = Math.max(1, c.getInt("storage.autosave-seconds", 5));

        placementOffset = c.getDouble("placement.offset", 0.3);
        pitchWall = (float) c.getDouble("placement.default-pitch.wall", 15);
        pitchCeiling = (float) c.getDouble("placement.default-pitch.ceiling", 35);
        pitchFloor = (float) c.getDouble("placement.default-pitch.floor", 0);
        yawSnap = (float) Math.max(0, c.getDouble("placement.yaw-snap", 15));
        minSpacing = c.getDouble("placement.min-spacing", 0.6);

        playersCanDamage = c.getBoolean("damage.players-can-damage", true);
        meleeDamage = c.getDouble("damage.melee", 2.0);
        projectileDamage = c.getDouble("damage.projectile", 4.0);
        explosionDamage = c.getDouble("damage.explosion", 20.0);
        explosionRadius = c.getDouble("damage.explosion-radius", 5.0);
        damagedThreshold = c.getDouble("damage.damaged-threshold", 0.5);

        repairMaterial = material(c.getString("repair.item", "IRON_INGOT"), Material.IRON_INGOT, log);
        repairModel = key(c.getString("repair.item-model", ""));
        repairAmount = c.getDouble("repair.amount", 5.0);
        repairConsumes = c.getBoolean("repair.consume", true);

        ptzUpdateTicks = Math.max(1, c.getInt("ptz.update-ticks", 2));
        motorSound = c.getString("ptz.motor-sound", "cctv:camera.motor");
        motorVolume = (float) c.getDouble("ptz.motor-volume", 0.6);
        motorPitch = (float) c.getDouble("ptz.motor-pitch", 1.0);
        motorSoundInterval = Math.max(1, c.getInt("ptz.motor-sound-interval", 10));

        itemMaterial = material(c.getString("item.material", "PAPER"), Material.PAPER, log);
        hitboxWidth = (float) c.getDouble("hitbox.width", 0.5);
        hitboxHeight = (float) c.getDouble("hitbox.height", 0.5);

        attachDelayTicks = Math.max(1, c.getInt("view.attach-delay-ticks", 3));
        transitionTicks = Math.max(0, c.getInt("view.transition-ticks", 8));
        exitTicks = Math.max(0, c.getInt("view.exit-transition-ticks", 6));
        lostTicks = Math.max(0, c.getInt("view.signal-lost-ticks", 20));
        bodyMode = c.getString("view.body", c.getBoolean("view.body-double", true) ? "auto" : "none")
                .toLowerCase(Locale.ROOT);
        bodyDouble = !bodyMode.equals("none");
        forwardDamage = c.getBoolean("view.forward-damage", true);
        switchOnLost = c.getString("view.on-camera-lost", "switch").equalsIgnoreCase("switch");
        switchSkipsOffline = c.getBoolean("view.switch-skips-offline", true);
        zoomMode = c.getString("view.zoom.mode", "dolly").toLowerCase(Locale.ROOT);
        zoomBlocksPerLevel = Math.max(0.5, c.getDouble("view.zoom.blocks-per-level", 5));
        ptzLookahead = (float) Math.max(0.05, c.getDouble("view.ptz-lookahead-seconds", 0.15));
        helpSeconds = Math.max(0, c.getInt("view.help-seconds", 4));
        requirePackForEffects = c.getBoolean("view.require-pack-for-effects", true);
        switchSound = c.getString("view.sounds.switch", "cctv:feed.switch");
        lostSound = c.getString("view.sounds.lost", "cctv:signal.lost");

        hudHalfWidth = (float) Math.max(40, c.getDouble("hud.half-width", 100));
        hudDateFormat = c.getString("hud.date-format", "yyyy-MM-dd");
        hudTimeFormat = c.getString("hud.time-format", "HH:mm:ss");

        effectsBackend = c.getString("effects.backend", "auto").toLowerCase(Locale.ROOT);
        weakSignalBelow = c.getDouble("effects.weak-signal-below", 0.5);
        criticalSignalBelow = c.getDouble("effects.critical-signal-below", 0.25);

        packDelivery = c.getString("pack.delivery", "http").toLowerCase(Locale.ROOT);
        packHttpPort = c.getInt("pack.http.port", 8164);
        packHttpBind = c.getString("pack.http.bind", "0.0.0.0");
        packPublicHost = c.getString("pack.http.public-host", "");
        packUrl = c.getString("pack.url", "");
        packRequired = c.getBoolean("pack.required", false);
        packPrompt = c.getString("pack.prompt", "");
        packTrustAny = c.getBoolean("pack.trust-any-pack", false);
        packSourceFolder = c.getString("pack.source-folder", "");

        Map<String, CameraType> parsed = new LinkedHashMap<>();
        ConfigurationSection ts = c.getConfigurationSection("types");
        if (ts != null) {
            for (String k : ts.getKeys(false)) {
                ConfigurationSection t = ts.getConfigurationSection(k);
                if (t == null || !t.getBoolean("enabled", true)) continue;
                CameraType type = type(k.toLowerCase(Locale.ROOT), t, log);
                if (type != null) parsed.put(type.key(), type);
            }
        }
        if (parsed.isEmpty()) {
            log.warning("config.yml defines no camera types; adding a built-in 'dome' so the plugin stays usable");
            parsed.put("dome", new CameraType("dome", Component.text("Dome Camera"),
                    NamespacedKey.fromString("cctv:camera_dome"), null, NamespacedKey.fromString("cctv:camera_broken"),
                    NamespacedKey.fromString("cctv:camera_dome"), false, 70, -45, 45, -20, 40, 30,
                    List.of(1f), "standard", "auto", 20, 0, 1f, null));
        }
        types = Collections.unmodifiableMap(parsed);
    }

    public static CctvConfig load(FileConfiguration c, Logger log) {
        return new CctvConfig(c, log);
    }

    private static @Nullable CameraType type(String key, ConfigurationSection t, Logger log) {
        NamespacedKey head = key(t.getString("model.head", "cctv:camera_" + key));
        if (head == null) {
            log.warning("camera type '" + key + "': model.head is not a valid id, type skipped");
            return null;
        }
        NamespacedKey base = key(t.getString("model.base", ""));
        NamespacedKey broken = key(t.getString("model.broken", "cctv:camera_broken"));
        NamespacedKey placer = key(t.getString("model.placer", head.asString()));
        List<Float> zoom = new ArrayList<>();
        for (Object o : t.getList("zoom-levels", List.of(1.0))) {
            if (o instanceof Number n && n.floatValue() >= 1f) zoom.add(n.floatValue());
        }
        if (zoom.isEmpty()) zoom.add(1f);
        float panMin = (float) t.getDouble("pan.min", -45);
        float panMax = (float) t.getDouble("pan.max", 45);
        float tiltMin = (float) t.getDouble("tilt.min", -20);
        float tiltMax = (float) t.getDouble("tilt.max", 40);
        return new CameraType(key,
                MiniMessage.miniMessage().deserialize(t.getString("name", key)),
                head, base, broken == null ? head : broken, placer == null ? head : placer,
                t.getBoolean("ptz", false),
                (float) t.getDouble("fov", 70),
                Math.min(panMin, panMax), Math.max(panMin, panMax),
                Math.min(tiltMin, tiltMax), Math.max(tiltMin, tiltMax),
                (float) Math.max(1, t.getDouble("pan-speed", 30)),
                List.copyOf(zoom),
                t.getString("look", "standard"),
                t.getString("fallback-anchor", "auto").toLowerCase(Locale.ROOT),
                Math.max(1, t.getDouble("max-health", 20)),
                Math.max(0, t.getInt("clearance", 0)),
                (float) t.getDouble("model-scale", 1.0),
                blankToNull(t.getString("model.bbmodel", "")));
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static @Nullable NamespacedKey key(@Nullable String s) {
        if (s == null || s.isBlank()) return null;
        return NamespacedKey.fromString(s.toLowerCase(Locale.ROOT));
    }

    private static Material material(@Nullable String name, Material fallback, Logger log) {
        Material m = name == null ? null : Material.matchMaterial(name);
        if (m == null || !m.isItem()) {
            log.warning("'" + name + "' is not an item material, using " + fallback);
            return fallback;
        }
        return m;
    }
}

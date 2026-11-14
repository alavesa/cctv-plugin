package fi.alavesa.cctv.camera;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * Turns PTZ heads toward their target at the type's pan speed. Runs every {@code updateTicks} ticks and only touches
 * cameras that are moving. Each step sends one display transformation with an interpolation duration equal to the
 * step interval, so the client blends between steps and the turn looks continuous.
 */
final class PtzMotor {

    private final CameraManager manager;
    private final CameraParts parts;
    private final Set<Camera> moving = new LinkedHashSet<>();
    private @Nullable BukkitTask task;
    private int updateTicks = 2;

    PtzMotor(CameraManager manager, CameraParts parts) {
        this.manager = manager;
        this.parts = parts;
    }

    void start(Plugin plugin, int updateTicks) {
        this.updateTicks = updateTicks;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, updateTicks, updateTicks);
    }

    void restart(Plugin plugin, int updateTicks) {
        stop();
        start(plugin, updateTicks);
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    void wake(Camera c) {
        if (moving.add(c)) c.motorTimer = 0;
        c.moving = true;
    }

    void forget(Camera c) {
        moving.remove(c);
        c.moving = false;
    }

    private void tick() {
        if (moving.isEmpty()) return;
        var cfg = manager.config();
        Iterator<Camera> it = moving.iterator();
        while (it.hasNext()) {
            Camera c = it.next();
            float step = c.type.panSpeed() * updateTicks / 20f;
            c.pan = approach(c.pan, c.targetPan, step);
            c.tilt = approach(c.tilt, c.targetTilt, step);
            parts.updateHead(c, updateTicks);
            if (c.motorTimer <= 0 && !cfg.motorSound.isBlank()) {
                World w = c.bukkitWorld();
                if (w != null) {
                    w.playSound(new Location(w, c.x, c.y, c.z), cfg.motorSound, SoundCategory.BLOCKS,
                            cfg.motorVolume, cfg.motorPitch);
                }
                c.motorTimer = cfg.motorSoundInterval;
            }
            c.motorTimer -= updateTicks;
            if (c.pan == c.targetPan && c.tilt == c.targetTilt) {
                c.moving = false;
                it.remove();
                manager.markDirty();
            }
        }
    }

    private static float approach(float from, float to, float step) {
        float d = to - from;
        return Math.abs(d) <= step ? to : from + Math.signum(d) * step;
    }
}

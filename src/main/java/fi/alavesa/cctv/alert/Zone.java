package fi.alavesa.cctv.alert;

import org.bukkit.Location;
import org.bukkit.util.BoundingBox;

/** A restricted area watched by the cameras of one network (group). */
public record Zone(String name, String world, BoundingBox box, String group) {

    public boolean contains(Location l) {
        return l.getWorld() != null && l.getWorld().getName().equals(world) && box.contains(l.toVector());
    }
}

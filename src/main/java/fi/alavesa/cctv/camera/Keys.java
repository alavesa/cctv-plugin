package fi.alavesa.cctv.camera;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/** PersistentDataContainer keys, all under this plugin's namespace ("cctv"). */
public final class Keys {

    /** On every camera entity: the camera id. */
    public final NamespacedKey camera;
    /** On every camera entity: base | head | hit. */
    public final NamespacedKey part;
    /** On placement items: the camera type key. */
    public final NamespacedKey placer;
    /** On placement items: the group the new camera joins. */
    public final NamespacedKey placerGroup;

    public Keys(Plugin plugin) {
        camera = new NamespacedKey(plugin, "camera");
        part = new NamespacedKey(plugin, "part");
        placer = new NamespacedKey(plugin, "placer");
        placerGroup = new NamespacedKey(plugin, "placer_group");
    }
}

package fi.alavesa.cctv.gui;

import fi.alavesa.cctv.camera.CameraManager;
import fi.alavesa.cctv.config.Messages;
import fi.alavesa.cctv.monitor.MonitorManager;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** What the menus need from the rest of the plugin. */
public record GuiContext(Plugin plugin, CameraManager cameras, MonitorManager monitors,
                         Supplier<ItemStack> terminalPlacer, Supplier<Messages> messages,
                         Predicate<Player> packLoaded, Function<String, Boolean> alerting) {
}

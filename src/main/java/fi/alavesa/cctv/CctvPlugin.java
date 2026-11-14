package fi.alavesa.cctv;

import fi.alavesa.cctv.api.CctvApi;
import fi.alavesa.cctv.camera.CameraListener;
import fi.alavesa.cctv.camera.CameraManager;
import fi.alavesa.cctv.command.CctvCommand;
import fi.alavesa.cctv.config.CctvConfig;
import fi.alavesa.cctv.config.Messages;
import fi.alavesa.cctv.alert.AlertService;
import fi.alavesa.cctv.effect.PostEffects;
import fi.alavesa.cctv.gui.GuiContext;
import fi.alavesa.cctv.gui.MenuListener;
import fi.alavesa.cctv.terminal.TerminalManager;
import fi.alavesa.cctv.monitor.MonitorConfig;
import fi.alavesa.cctv.monitor.MonitorListener;
import fi.alavesa.cctv.monitor.MonitorManager;
import fi.alavesa.cctv.pack.PackService;
import fi.alavesa.cctv.view.ViewManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class CctvPlugin extends JavaPlugin {

    private CameraManager cameras;
    private PackService packs;
    private ViewManager views;
    private MonitorManager monitors;
    private TerminalManager terminals;
    private AlertService alerts;
    private Messages messages;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new java.io.File(getDataFolder(), "models/example_ptz.bbmodel").exists()) {
            saveResource("models/example_ptz.bbmodel", false);
        }
        CctvConfig cfg = CctvConfig.load(getConfig(), getLogger());
        messages = new Messages(getConfig());

        cameras = new CameraManager(this, cfg);
        cameras.load();
        packs = new PackService(getLogger(), getFile(), getDataFolder().toPath(), cfg, cameras::modelPackFiles);
        packs.start();
        views = new ViewManager(this, cameras, packs, PostEffects.create(cfg.effectsBackend, getLogger()),
                () -> messages, cfg);
        cameras.setViewBridge(views);
        views.start();
        getLogger().info("Post effects backend: " + views.effects().name());
        monitors = new MonitorManager(this, cameras, MonitorConfig.load(getConfig(), getLogger()));
        monitors.setViewStarter(cameras::startViewing);
        monitors.load();
        terminals = new TerminalManager(this, cameras, () -> messages);
        terminals.config(getConfig());
        terminals.load();
        alerts = new AlertService(this, cameras, () -> messages, getConfig());
        alerts.load();
        views.setAlertSource(g -> {
            AlertService.Active a = alerts.active(g);
            return a == null ? null : a.zone() + " " + a.cameraId();
        });
        GuiContext gui = new GuiContext(this, cameras, monitors, terminals::placer, () -> messages, packs::loaded,
                cameras::alerting);
        terminals.setGui(gui);

        var pm = getServer().getPluginManager();
        pm.registerEvents(new CameraListener(cameras, () -> messages), this);
        pm.registerEvents(packs, this);
        pm.registerEvents(views, this);
        pm.registerEvents(new MonitorListener(monitors, () -> messages), this);
        pm.registerEvents(terminals, this);
        pm.registerEvents(new MenuListener(), this);
        getServer().getServicesManager().register(CctvApi.class, cameras, this, ServicePriority.Normal);

        CctvCommand command = new CctvCommand(cameras, views, packs, monitors, () -> messages, this::reload);
        command.extras(gui, terminals, alerts);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register("cctv", "CCTV cameras and monitors", List.of("cam"), command));
    }

    @Override
    public void onDisable() {
        // order matters: put viewers back first (they spectate camera anchors), then remove the cameras' entities
        if (views != null) views.shutdown();
        if (monitors != null) monitors.shutdown();
        if (terminals != null) terminals.shutdown();
        if (alerts != null) alerts.shutdown();
        if (cameras != null) cameras.shutdown();
        if (packs != null) packs.stop();
        getServer().getServicesManager().unregisterAll(this);
    }

    /** /cctv reload: config.yml (types, settings, messages, pack). cameras.yml stays authoritative in memory. */
    public void reload() {
        reloadConfig();
        CctvConfig cfg = CctvConfig.load(getConfig(), getLogger());
        messages = new Messages(getConfig());
        cameras.reload(cfg);
        packs.reload(cfg);
        views.reload(cfg, PostEffects.create(cfg.effectsBackend, getLogger()));
        monitors.reload(MonitorConfig.load(getConfig(), getLogger()));
        terminals.config(getConfig());
        alerts.config(getConfig());
    }

    public CctvApi api() {
        return cameras;
    }

    /** Monitor manager (not part of the stable API yet). */
    public MonitorManager monitors() {
        return monitors;
    }
}

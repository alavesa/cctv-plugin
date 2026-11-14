package fi.alavesa.cctv.view;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Body double as a FancyNpcs player NPC (github.com/FancyMcPlugins/FancyNpcs) with the viewer's current skin and
 * gear. Same approach as the Terminal plugin's NpcBridge, which runs on the SCP server: FancyNpcs is reached by
 * reflection only, so it stays an optional dependency and any FancyNpcs version with this API works.
 *
 * <p>FancyNpcs NPCs are packet-only: no Bukkit entity, no damage events. A hit is caught through FancyNpcs'
 * {@code NpcInteractEvent} (LEFT_CLICK). Projectiles and explosions can't hit it; use the Mannequin body if that
 * matters.
 *
 * <p>NPC names start with {@value #PREFIX}. Terminal purges NPCs starting with "cctv_", so the two plugins never
 * remove each other's doubles.
 */
final class NpcBody {

    static final String PREFIX = "cctvbody_";

    private static boolean bound;
    private static Object plugin;
    private static Method adapter, manager, register, removeNpc, getNpc, create, spawnAll, removeAll, getAll, getData,
            dataName, setSkinData, setName, setTab, setTurn, setEquip;
    private static Constructor<?> dataCtor, skinCtor;
    private static Object skinAuto;
    private static Class<?> slotEnum;
    private static final Map<String, Object> live = new ConcurrentHashMap<>();

    private NpcBody() {
    }

    /** Binds the FancyNpcs API if the plugin is installed. Call on enable. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static boolean bind(Plugin owner, BiConsumer<Player, String> onHit) {
        Logger log = owner.getLogger();
        if (Bukkit.getPluginManager().getPlugin("FancyNpcs") == null) return false;
        try {
            Class<?> pluginClass = Class.forName("de.oliver.fancynpcs.api.FancyNpcsPlugin");
            plugin = pluginClass.getMethod("get").invoke(null);
            adapter = pluginClass.getMethod("getNpcAdapter");
            manager = pluginClass.getMethod("getNpcManager");
            Class<?> data = Class.forName("de.oliver.fancynpcs.api.NpcData");
            dataCtor = data.getConstructor(String.class, UUID.class, Location.class);
            setName = data.getMethod("setDisplayName", String.class);
            setTab = data.getMethod("setShowInTab", boolean.class);
            setTurn = data.getMethod("setTurnToPlayer", boolean.class);
            setEquip = data.getMethod("setEquipment", Map.class);
            dataName = data.getMethod("getName");
            Class<?> skin = Class.forName("de.oliver.fancynpcs.api.skins.SkinData");
            Class<?> variant = Class.forName("de.oliver.fancynpcs.api.skins.SkinData$SkinVariant");
            skinCtor = skin.getConstructor(String.class, variant, String.class, String.class);
            skinAuto = Enum.valueOf((Class<Enum>) variant, "AUTO");
            setSkinData = data.getMethod("setSkinData", skin);
            slotEnum = Class.forName("de.oliver.fancynpcs.api.utils.NpcEquipmentSlot");
            Class<?> npc = Class.forName("de.oliver.fancynpcs.api.Npc");
            create = npc.getMethod("create");
            spawnAll = npc.getMethod("spawnForAll");
            removeAll = npc.getMethod("removeForAll");
            getData = npc.getMethod("getData");
            Class<?> mgr = Class.forName("de.oliver.fancynpcs.api.NpcManager");
            register = mgr.getMethod("registerNpc", npc);
            removeNpc = mgr.getMethod("removeNpc", npc);
            getNpc = mgr.getMethod("getNpc", String.class);
            getAll = mgr.getMethod("getAllNpcs");
            bound = true;
        } catch (Throwable t) {
            log.warning("FancyNpcs is installed but its API didn't bind (" + t + "); body doubles use Mannequins");
            bound = false;
            return false;
        }
        // hits on the double: FancyNpcs handles the attack packet itself and reports it as NpcInteractEvent
        try {
            Class<? extends Event> event = (Class<? extends Event>)
                    Class.forName("de.oliver.fancynpcs.api.events.NpcInteractEvent");
            Method type = event.getMethod("getInteractionType");
            Method npcOf = event.getMethod("getNpc");
            Method playerOf = event.getMethod("getPlayer");
            EventExecutor exec = (listener, e) -> {
                try {
                    if (!event.isInstance(e)) return;
                    Object t = type.invoke(e);
                    if (t == null || !t.toString().equals("LEFT_CLICK")) return;
                    Object name = dataName.invoke(getData.invoke(npcOf.invoke(e)));
                    if (name instanceof String s && s.startsWith(PREFIX) && playerOf.invoke(e) instanceof Player p) {
                        onHit.accept(p, s);
                    }
                } catch (ReflectiveOperationException ignored) {
                    // a FancyNpcs version without these getters: hits just don't register
                }
            };
            Bukkit.getPluginManager().registerEvent(event, new Listener() { }, EventPriority.NORMAL, exec, owner);
        } catch (Throwable t) {
            log.warning("FancyNpcs NpcInteractEvent not found (" + t + "); hits on NPC doubles won't register");
        }
        purgeOrphans(log);
        log.info("FancyNpcs found: camera viewers leave a player-model NPC behind");
        return true;
    }

    static boolean available() {
        return bound;
    }

    /** Spawns the double; returns its NPC name, or null on failure (the caller falls back to a Mannequin). */
    @SuppressWarnings("unchecked")
    static @Nullable String spawn(Player source, Location at, Logger log) {
        if (!bound) return null;
        try {
            String name = PREFIX + source.getName() + "_" + (System.nanoTime() & 0xFFFFFF);
            Object data = dataCtor.newInstance(name, source.getUniqueId(), at);
            // the CURRENT textures (disguises included); without any (offline-mode accounts) keep FancyNpcs'
            // default skin: setSkin(name) would make FancyNpcs look the name up at Mojang, which throws for them
            String[] tex = currentSkin(source);
            if (tex != null) {
                setSkinData.invoke(data, skinCtor.newInstance(source.getName(), skinAuto, tex[0], tex[1]));
            }
            setName.invoke(data, "<empty>"); // FancyNpcs' marker for "no name tag"
            setTab.invoke(data, false);
            setTurn.invoke(data, false);
            Map<Object, ItemStack> gear = new HashMap<>();
            for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                    EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND}) {
                ItemStack it = source.getEquipment().getItem(slot);
                if (!it.isEmpty()) gear.put(slot(slot), it.clone());
            }
            if (!gear.isEmpty()) setEquip.invoke(data, gear);
            Object npc = ((java.util.function.Function<Object, Object>) adapter.invoke(plugin)).apply(data);
            create.invoke(npc);
            register.invoke(manager.invoke(plugin), npc);
            spawnAll.invoke(npc);
            live.put(name, npc);
            return name;
        } catch (Throwable t) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null
                    ? t.getCause() : t;
            log.warning("FancyNpcs body double failed (" + cause + "); using a Mannequin");
            return null;
        }
    }

    static void remove(@Nullable String name, Logger log) {
        if (name == null || !bound) return;
        try {
            Object mgr = manager.invoke(plugin);
            Object npc = live.remove(name);
            if (npc == null) npc = getNpc.invoke(mgr, name);
            if (npc != null) {
                removeAll.invoke(npc);
                removeNpc.invoke(mgr, npc);
            }
        } catch (Throwable t) {
            log.warning("FancyNpcs body double removal failed: " + t);
        }
    }

    /** Removes doubles left over from a crash. Only our prefix is touched. */
    private static void purgeOrphans(Logger log) {
        try {
            Object mgr = manager.invoke(plugin);
            for (Object npc : new ArrayList<>((Collection<?>) getAll.invoke(mgr))) {
                Object name = dataName.invoke(getData.invoke(npc));
                if (name instanceof String s && s.startsWith(PREFIX)) {
                    removeAll.invoke(npc);
                    removeNpc.invoke(mgr, npc);
                }
            }
        } catch (Throwable t) {
            log.warning("FancyNpcs orphan purge failed: " + t);
        }
    }

    /** The CURRENT profile textures (picks up disguises), or null for a default skin. */
    private static String @Nullable [] currentSkin(Player p) {
        for (var prop : p.getPlayerProfile().getProperties()) {
            if (prop.getName().equals("textures")) return new String[]{prop.getValue(), prop.getSignature()};
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object slot(EquipmentSlot slot) {
        String n = switch (slot) {
            case HEAD -> "HELMET";
            case CHEST -> "CHEST";
            case LEGS -> "LEGGINGS";
            case FEET -> "BOOTS";
            case OFF_HAND -> "OFFHAND";
            default -> "MAINHAND";
        };
        return Enum.valueOf((Class<Enum>) slotEnum, n);
    }
}

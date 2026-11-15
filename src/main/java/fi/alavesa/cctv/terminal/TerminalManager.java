package fi.alavesa.cctv.terminal;

import fi.alavesa.cctv.camera.CameraManager;
import fi.alavesa.cctv.camera.Orientation;
import fi.alavesa.cctv.config.Messages;
import fi.alavesa.cctv.gui.BrowserMenu;
import fi.alavesa.cctv.gui.GuiContext;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Security terminals. Placing one turns the block into an invisible barrier with a console model on it. Right-click:
 * permission cctv.terminal.use, then the terminal's clearance level through the ClearanceProvider (keycards, LuckPerms
 * ...), then the camera browser, locked to the terminal's network if it has one. Sneak-left-click with
 * cctv.terminal.pickup takes it down. Stored in terminals.yml.
 */
public final class TerminalManager implements Listener {

    private final Plugin plugin;
    private final CameraManager cameras;
    private final Supplier<Messages> messages;
    private final File file;
    private final NamespacedKey placerKey;
    private final NamespacedKey terminalKey;
    private final Map<String, Terminal> byId = new LinkedHashMap<>();
    private final Map<String, Terminal> byBlock = new LinkedHashMap<>();
    private @Nullable GuiContext gui;
    private NamespacedKey model = NamespacedKey.fromString("cctv:terminal");
    private int defaultClearance;
    private int nextId = 1;

    public TerminalManager(Plugin plugin, CameraManager cameras, Supplier<Messages> messages) {
        this.plugin = plugin;
        this.cameras = cameras;
        this.messages = messages;
        this.file = new File(plugin.getDataFolder(), "terminals.yml");
        this.placerKey = new NamespacedKey(plugin, "terminal_placer");
        this.terminalKey = new NamespacedKey(plugin, "terminal");
    }

    public void setGui(GuiContext gui) {
        this.gui = gui;
    }

    public void config(org.bukkit.configuration.file.FileConfiguration c) {
        NamespacedKey m = NamespacedKey.fromString(c.getString("terminals.model", "cctv:terminal"));
        if (m != null) model = m;
        defaultClearance = Math.max(0, c.getInt("terminals.default-clearance", 0));
    }

    // ------------------------------------------------------------------ persistence

    public void load() {
        if (file.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
            nextId = Math.max(1, y.getInt("next-id", 1));
            ConfigurationSection ts = y.getConfigurationSection("terminals");
            if (ts != null) {
                for (String id : ts.getKeys(false)) {
                    ConfigurationSection s = ts.getConfigurationSection(id);
                    if (s == null) continue;
                    Terminal t = new Terminal(id, s.getString("world", "world"), s.getInt("x"), s.getInt("y"),
                            s.getInt("z"), BlockFace.valueOf(s.getString("facing", "SOUTH")));
                    t.group = s.getString("group");
                    t.clearance = s.getInt("clearance", 0);
                    register(t);
                }
            }
        }
        for (Terminal t : byId.values()) spawn(t);
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-id", nextId);
        for (Terminal t : byId.values()) {
            String p = "terminals." + t.id + ".";
            y.set(p + "world", t.world);
            y.set(p + "x", t.x);
            y.set(p + "y", t.y);
            y.set(p + "z", t.z);
            y.set(p + "facing", t.facing.name());
            y.set(p + "group", t.group);
            y.set(p + "clearance", t.clearance);
        }
        try {
            y.save(file); // small, only on placement/edits
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "could not save terminals.yml", e);
        }
    }

    public void shutdown() {
        for (Terminal t : byId.values()) despawn(t);
    }

    private void register(Terminal t) {
        byId.put(t.id, t);
        byBlock.put(key(t.world, t.x, t.y, t.z), t);
    }

    private static String key(String w, int x, int y, int z) {
        return w + ":" + x + ":" + y + ":" + z;
    }

    public Collection<Terminal> terminals() {
        return byId.values();
    }

    public @Nullable Terminal get(String id) {
        return byId.get(id.toLowerCase(Locale.ROOT));
    }

    public @Nullable Terminal at(Block b) {
        return byBlock.get(key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
    }

    // ------------------------------------------------------------------ items & placement

    public ItemStack placer() {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(model);
        m.itemName(Component.text("Security Terminal", NamedTextColor.WHITE));
        m.lore(List.of(Component.text("Place on top of a block", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        m.getPersistentDataContainer().set(placerKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    private boolean isPlacer(@Nullable ItemStack it) {
        return it != null && !it.isEmpty() && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(placerKey);
    }

    public void setGroup(Terminal t, @Nullable String group) {
        t.group = group == null || group.isBlank() || group.equals("*") ? null : group.toLowerCase(Locale.ROOT);
        save();
    }

    public void setClearance(Terminal t, int level) {
        t.clearance = Math.max(0, level);
        save();
    }

    public void remove(Terminal t) {
        byId.remove(t.id);
        byBlock.remove(key(t.world, t.x, t.y, t.z));
        despawn(t);
        World w = Bukkit.getWorld(t.world);
        if (w != null && w.getBlockAt(t.x, t.y, t.z).getType() == Material.BARRIER) {
            w.getBlockAt(t.x, t.y, t.z).setType(Material.AIR, false);
        }
        save();
    }

    private void spawn(Terminal t) {
        if (t.display != null) return;
        World w = Bukkit.getWorld(t.world);
        if (w == null || !w.isChunkLoaded(t.x >> 4, t.z >> 4)) return;
        Quaternionf rot = Orientation.head(Orientation.faceYaw(t.facing), 0);
        t.display = w.spawn(new Location(w, t.x + 0.5, t.y + 0.5, t.z + 0.5), ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBillboard(Display.Billboard.FIXED);
            ItemStack it = new ItemStack(Material.PAPER);
            ItemMeta m = it.getItemMeta();
            m.setItemModel(model);
            it.setItemMeta(m);
            d.setItemStack(it);
            d.setTransformation(new Transformation(new Vector3f(), rot, new Vector3f(1, 1, 1), new Quaternionf()));
            d.getPersistentDataContainer().set(terminalKey, PersistentDataType.STRING, t.id);
        }).getUniqueId();
    }

    private void despawn(Terminal t) {
        Entity e = t.display == null ? null : Bukkit.getEntity(t.display);
        if (e != null) e.remove();
        t.display = null;
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        Player p = e.getPlayer();
        Block b = e.getClickedBlock();
        Terminal t = at(b);
        if (t != null) {
            e.setCancelled(true);
            if (e.getAction() == Action.LEFT_CLICK_BLOCK) {
                if (p.isSneaking() && p.hasPermission("cctv.terminal.pickup")) pickup(p, t);
                return;
            }
            if (e.getAction() == Action.RIGHT_CLICK_BLOCK) use(p, t);
            return;
        }
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && isPlacer(e.getItem())) {
            e.setCancelled(true);
            if (!p.hasPermission("cctv.terminal.place")) {
                messages.get().send(p, "no-permission");
                return;
            }
            if (e.getBlockFace() != BlockFace.UP) {
                messages.get().send(p, "terminal-place-top");
                return;
            }
            Block at = b.getRelative(BlockFace.UP);
            if (!at.getType().isAir()) {
                messages.get().send(p, "place-blocked");
                return;
            }
            Terminal nt = new Terminal("term-" + nextId++, at.getWorld().getName(), at.getX(), at.getY(), at.getZ(),
                    p.getFacing().getOppositeFace());
            nt.clearance = defaultClearance;
            at.setType(Material.BARRIER, false);
            register(nt);
            spawn(nt);
            save();
            if (p.getGameMode() != GameMode.CREATIVE) e.getItem().subtract();
            messages.get().send(p, "terminal-placed", Messages.p("id", nt.id));
        }
    }

    private void use(Player p, Terminal t) {
        if (!p.hasPermission("cctv.terminal.use") || !cameras.clearanceProvider().hasClearance(p, t.clearance)) {
            messages.get().send(p, "terminal-denied", Messages.p("clearance", t.clearance));
            p.playSound(p.getLocation(), "minecraft:block.note_block.bass", 0.8f, 0.6f);
            return;
        }
        if (gui == null) return;
        p.playSound(p.getLocation(), "minecraft:block.note_block.hat", 0.6f, 1.8f);
        new BrowserMenu(gui, p, false, t.group, true).open();
    }

    private void pickup(Player p, Terminal t) {
        remove(t);
        if (p.getGameMode() != GameMode.CREATIVE) {
            p.getInventory().addItem(placer()).values().forEach(left -> p.getWorld().dropItem(p.getLocation(), left));
        }
        messages.get().send(p, "terminal-removed", Messages.p("id", t.id));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Terminal t = at(e.getBlock());
        if (t == null) return;
        e.setCancelled(true);
        if (e.getPlayer().hasPermission("cctv.terminal.pickup")) pickup(e.getPlayer(), t);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        Chunk c = e.getChunk();
        for (Terminal t : byId.values()) {
            if (t.world.equals(c.getWorld().getName()) && t.x >> 4 == c.getX() && t.z >> 4 == c.getZ()) spawn(t);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent e) {
        Chunk c = e.getChunk();
        for (Terminal t : byId.values()) {
            if (t.world.equals(c.getWorld().getName()) && t.x >> 4 == c.getX() && t.z >> 4 == c.getZ()) despawn(t);
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            String id = en.getPersistentDataContainer().get(terminalKey, PersistentDataType.STRING);
            if (id == null) continue;
            Terminal t = byId.get(id);
            if (t == null || !en.getUniqueId().equals(t.display)) en.remove();
        }
    }
}

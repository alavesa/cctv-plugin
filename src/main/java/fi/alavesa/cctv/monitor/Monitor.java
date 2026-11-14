package fi.alavesa.cctv.monitor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.map.MapView;
import org.jspecify.annotations.Nullable;

/** One monitor (1..8 x 1..8 map tiles). Record fields are persisted; the rest is runtime state. Main thread only. */
public final class Monitor {

    public enum Mode { LIVE, SNAPSHOT, SHADER, QUAD }

    final String id;
    String typeKey;
    MonitorConfig.Type type;
    final String world;
    /** the block the bottom-left tile's frame hangs on */
    final int bx, by, bz;
    /** direction the screen faces */
    final BlockFace facing;
    final List<String> cameras = new ArrayList<>();
    int cycleSeconds;
    Mode mode = Mode.LIVE;
    boolean powered = true;
    int[] mapIds;

    // runtime
    @Nullable MapView[] views;
    @Nullable TileRenderer[] renderers;
    byte[][] tiles;
    @Nullable UUID[] frames;
    @Nullable UUID bezelOne;
    @Nullable UUID[] bezels;
    int cycleIndex;
    long cycleSince;
    long nextFrameAt;
    long bootUntil;
    long staticUntil;
    boolean rendering;
    @Nullable String lastCamera;
    Screen.State shown;
    String shownKey = "";
    final List<UUID> watchers = new ArrayList<>();
    long humAt;
    // stats
    double renderMsAvg;
    long frameCount;
    long bytesSent;

    Monitor(String id, String typeKey, MonitorConfig.Type type, String world, int bx, int by, int bz, BlockFace facing) {
        this.id = id;
        this.typeKey = typeKey;
        this.type = type;
        this.world = world;
        this.bx = bx;
        this.by = by;
        this.bz = bz;
        this.facing = facing;
        this.mapIds = new int[type.width() * type.height()];
        java.util.Arrays.fill(mapIds, -1);
        this.tiles = new byte[mapIds.length][Screen.SIZE * Screen.SIZE];
    }

    public String id() {
        return id;
    }

    public MonitorConfig.Type type() {
        return type;
    }

    public List<String> cameras() {
        return List.copyOf(cameras);
    }

    public Mode mode() {
        return mode;
    }

    public boolean powered() {
        return powered;
    }

    public String worldName() {
        return world;
    }

    @Nullable World bukkitWorld() {
        return Bukkit.getWorld(world);
    }

    int tileCount() {
        return type.width() * type.height();
    }

    /** Right-hand direction for someone looking at the screen (cross(up, facing)). */
    int rightX() {
        return facing.getModZ();
    }

    int rightZ() {
        return -facing.getModX();
    }

    /** Block the frame of tile (i from the left, j from the bottom) hangs on. */
    int[] backBlock(int i, int j) {
        return new int[]{bx + rightX() * i, by + j, bz + rightZ() * i};
    }

    /** The camera this monitor shows right now (cycle aware), or null. */
    @Nullable String currentCamera() {
        if (cameras.isEmpty()) return null;
        return cameras.get(Math.floorMod(cycleIndex, cameras.size()));
    }
}

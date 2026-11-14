package fi.alavesa.cctv.monitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Fence;
import org.bukkit.block.data.type.Gate;
import org.bukkit.block.data.type.Ladder;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Snow;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.block.data.type.Wall;
import org.bukkit.block.data.type.WallSign;

/**
 * Shapes of the non-full blocks the monitor feed draws (slabs, stairs, fences, walls, doors, carpets, snow, ...), as
 * boxes in block space (0..1). Approximations of the vanilla outlines, good at monitor resolution. Which materials
 * are shaped is decided once in {@link BlockLook} ({@link #isShaped}); the boxes depend on the block state and are
 * cached per state. Thread-safe.
 */
final class Shapes {

    private static final double P = 1 / 16.0;
    private static final double[][] FULL = {{0, 0, 0, 1, 1, 1}};
    private static final Map<BlockData, double[][]> CACHE = new ConcurrentHashMap<>();

    private Shapes() {
    }

    /** True for materials drawn with {@link #boxes} (decided from a default block state, main thread). */
    static boolean isShaped(Material m, BlockData bd) {
        String n = m.name();
        if (bd instanceof Slab || bd instanceof Stairs || bd instanceof Snow || bd instanceof Wall
                || bd instanceof Door || bd instanceof TrapDoor || bd instanceof Gate || bd instanceof Bed
                || bd instanceof Ladder) return true;
        if (bd instanceof Fence && !n.contains("GLASS")) return true; // fences, iron bars; glass panes stay glass
        return n.endsWith("CARPET") || n.endsWith("PRESSURE_PLATE") || n.endsWith("CHEST") || n.endsWith("_SIGN")
                || n.equals("FARMLAND") || n.equals("DIRT_PATH") || n.equals("ENCHANTING_TABLE")
                || n.equals("DAYLIGHT_DETECTOR") || n.equals("STONECUTTER") || n.endsWith("CAMPFIRE")
                || n.endsWith("CAKE") || n.endsWith("LANTERN") || n.endsWith("TORCH") || n.endsWith("RAIL")
                || n.equals("REDSTONE_WIRE") || n.equals("REPEATER") || n.equals("COMPARATOR")
                || n.equals("LECTERN") || n.equals("CHAIN") || n.endsWith("ROD") || n.endsWith("BUTTON")
                || n.equals("LEVER") || n.equals("FLOWER_POT") || n.startsWith("POTTED_") || n.equals("CONDUIT")
                || (n.endsWith("HEAD") && !n.startsWith("PISTON")) || n.endsWith("SKULL") || n.endsWith("CANDLE") || n.equals("SCAFFOLDING")
                || n.equals("BREWING_STAND") || n.equals("BELL") || n.equals("GRINDSTONE") || n.equals("HOPPER");
    }

    static double[][] boxes(BlockData bd) {
        double[][] b = CACHE.get(bd);
        if (b == null) {
            b = compute(bd);
            CACHE.put(bd, b);
        }
        return b;
    }

    private static double[][] compute(BlockData bd) {
        String n = bd.getMaterial().name();
        List<double[]> out = new ArrayList<>(4);
        if (bd instanceof Slab s) {
            switch (s.getType()) {
                case BOTTOM -> out.add(new double[]{0, 0, 0, 1, 0.5, 1});
                case TOP -> out.add(new double[]{0, 0.5, 0, 1, 1, 1});
                default -> {
                    return FULL;
                }
            }
        } else if (bd instanceof Stairs s) {
            boolean top = s.getHalf() == Bisected.Half.TOP;
            out.add(top ? new double[]{0, 0.5, 0, 1, 1, 1} : new double[]{0, 0, 0, 1, 0.5, 1});
            double y0 = top ? 0 : 0.5, y1 = top ? 0.5 : 1;
            out.add(half(s.getFacing(), y0, y1));
        } else if (bd instanceof Snow s) {
            out.add(new double[]{0, 0, 0, 1, s.getLayers() / 8.0, 1});
        } else if (bd instanceof Wall w) {
            out.add(new double[]{4 * P, 0, 4 * P, 12 * P, 1, 12 * P});
            for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                Wall.Height h = w.getHeight(f);
                if (h != Wall.Height.NONE) out.add(arm(f, 5 * P, 11 * P, 0, h == Wall.Height.TALL ? 1 : 14 * P));
            }
        } else if (bd instanceof Gate g) {
            BlockFace f = g.getFacing();
            boolean ns = f == BlockFace.NORTH || f == BlockFace.SOUTH;
            if (g.isOpen()) {
                // open: only the two end posts
                out.add(ns ? new double[]{0, 5 * P, 7 * P, 2 * P, 1, 9 * P} : new double[]{7 * P, 5 * P, 0, 9 * P, 1, 2 * P});
                out.add(ns ? new double[]{14 * P, 5 * P, 7 * P, 1, 1, 9 * P} : new double[]{7 * P, 5 * P, 14 * P, 9 * P, 1, 1});
            } else {
                out.add(ns ? new double[]{0, 5 * P, 7 * P, 1, 1, 9 * P} : new double[]{7 * P, 5 * P, 0, 9 * P, 1, 1});
            }
        } else if (bd instanceof Fence f) {
            boolean bars = n.equals("IRON_BARS");
            double a = bars ? 7 * P : 6 * P, b = bars ? 9 * P : 10 * P;
            out.add(new double[]{a, 0, a, b, 1, b});
            for (BlockFace face : f.getFaces()) {
                if (bars) out.add(arm(face, 7 * P, 9 * P, 0, 1));
                else {
                    out.add(arm(face, 7 * P, 9 * P, 6 * P, 9 * P));
                    out.add(arm(face, 7 * P, 9 * P, 12 * P, 15 * P));
                }
            }
        } else if (bd instanceof Door d) {
            BlockFace side = d.getFacing().getOppositeFace();
            if (d.isOpen()) side = d.getHinge() == Door.Hinge.RIGHT ? ccw(d.getFacing()) : cw(d.getFacing());
            out.add(thin(side, 3 * P, 0, 1));
        } else if (bd instanceof TrapDoor t) {
            if (t.isOpen()) out.add(thin(t.getFacing().getOppositeFace(), 3 * P, 0, 1));
            else out.add(t.getHalf() == Bisected.Half.TOP ? new double[]{0, 13 * P, 0, 1, 1, 1}
                    : new double[]{0, 0, 0, 1, 3 * P, 1});
        } else if (bd instanceof Ladder l) {
            out.add(thin(l.getFacing().getOppositeFace(), 2 * P, 0, 1));
        } else if (bd instanceof Bed) {
            out.add(new double[]{0, 3 * P, 0, 1, 9 * P, 1});
        } else if (bd instanceof WallSign ws) {
            out.add(thin(ws.getFacing().getOppositeFace(), 2 * P, 4.5 * P, 12.5 * P));
        } else if (n.endsWith("_SIGN")) {
            out.add(new double[]{0, 8 * P, 7 * P, 1, 1, 9 * P});
            out.add(new double[]{7 * P, 0, 7 * P, 9 * P, 8 * P, 9 * P});
        } else if (n.endsWith("CARPET")) {
            out.add(new double[]{0, 0, 0, 1, P, 1});
        } else if (n.endsWith("PRESSURE_PLATE")) {
            out.add(new double[]{P, 0, P, 15 * P, P, 15 * P});
        } else if (n.endsWith("RAIL") || n.equals("REDSTONE_WIRE")) {
            out.add(new double[]{0, 0, 0, 1, P / 2, 1});
        } else if (n.equals("REPEATER") || n.equals("COMPARATOR") || n.equals("DAYLIGHT_DETECTOR")) {
            out.add(new double[]{0, 0, 0, 1, n.startsWith("DAYLIGHT") ? 6 * P : 2 * P, 1});
        } else if (n.endsWith("CHEST")) {
            out.add(new double[]{P, 0, P, 15 * P, 14 * P, 15 * P});
        } else if (n.equals("FARMLAND") || n.equals("DIRT_PATH")) {
            out.add(new double[]{0, 0, 0, 1, 15 * P, 1});
        } else if (n.equals("ENCHANTING_TABLE")) {
            out.add(new double[]{0, 0, 0, 1, 12 * P, 1});
        } else if (n.equals("STONECUTTER")) {
            out.add(new double[]{0, 0, 0, 1, 9 * P, 1});
        } else if (n.endsWith("CAMPFIRE")) {
            out.add(new double[]{0, 0, 0, 1, 7 * P, 1});
        } else if (n.endsWith("CAKE")) {
            out.add(new double[]{P, 0, P, 15 * P, 8 * P, 15 * P});
        } else if (n.endsWith("LANTERN")) {
            out.add(new double[]{5 * P, P, 5 * P, 11 * P, 9 * P, 11 * P});
        } else if (n.endsWith("TORCH")) {
            if (n.contains("WALL")) return new double[0][];
            out.add(new double[]{7 * P, 0, 7 * P, 9 * P, 10 * P, 9 * P});
        } else if (n.equals("CHAIN") || n.endsWith("ROD")) {
            out.add(new double[]{6.5 * P, 0, 6.5 * P, 9.5 * P, 1, 9.5 * P});
        } else if (n.equals("LECTERN")) {
            out.add(new double[]{0, 0, 0, 1, 2 * P, 1});
            out.add(new double[]{4 * P, 2 * P, 4 * P, 12 * P, 13 * P, 12 * P});
            out.add(new double[]{0, 10 * P, 0, 1, 14 * P, 1});
        } else if (n.equals("FLOWER_POT") || n.startsWith("POTTED_")) {
            out.add(new double[]{5 * P, 0, 5 * P, 11 * P, 6 * P, 11 * P});
        } else if (n.endsWith("HEAD") || n.endsWith("SKULL")) {
            if (n.contains("WALL")) return new double[0][];
            out.add(new double[]{4 * P, 0, 4 * P, 12 * P, 8 * P, 12 * P});
        } else if (n.endsWith("CANDLE")) {
            out.add(new double[]{7 * P, 0, 7 * P, 9 * P, 6 * P, 9 * P});
        } else if (n.equals("SCAFFOLDING")) {
            out.add(new double[]{0, 14 * P, 0, 1, 1, 1});
            out.add(new double[]{0, 0, 0, 2 * P, 1, 2 * P});
            out.add(new double[]{14 * P, 0, 14 * P, 1, 1, 1});
        } else if (n.equals("BREWING_STAND")) {
            out.add(new double[]{7 * P, 0, 7 * P, 9 * P, 14 * P, 9 * P});
        } else if (n.equals("HOPPER")) {
            out.add(new double[]{0, 10 * P, 0, 1, 1, 1});
            out.add(new double[]{4 * P, 4 * P, 4 * P, 12 * P, 10 * P, 12 * P});
        } else if (n.equals("BELL") || n.equals("GRINDSTONE") || n.equals("CONDUIT")) {
            out.add(new double[]{4 * P, 4 * P, 4 * P, 12 * P, 13 * P, 12 * P});
        } else if (n.endsWith("BUTTON") || n.equals("LEVER")) {
            Directional dd = bd instanceof Directional x ? x : null;
            out.add(dd == null ? new double[]{6 * P, 0, 6 * P, 10 * P, 2 * P, 10 * P}
                    : thin(dd.getFacing().getOppositeFace(), 2 * P, 6 * P, 10 * P));
        } else {
            return FULL;
        }
        return out.toArray(new double[0][]);
    }

    /** The half of the block on side {@code f} (for the upper step of stairs). */
    private static double[] half(BlockFace f, double y0, double y1) {
        return switch (f) {
            case NORTH -> new double[]{0, y0, 0, 1, y1, 0.5};
            case SOUTH -> new double[]{0, y0, 0.5, 1, y1, 1};
            case WEST -> new double[]{0, y0, 0, 0.5, y1, 1};
            default -> new double[]{0.5, y0, 0, 1, y1, 1};
        };
    }

    /** A plate of thickness {@code d} against side {@code f}, from y0 to y1. */
    private static double[] thin(BlockFace f, double d, double y0, double y1) {
        return switch (f) {
            case NORTH -> new double[]{0, y0, 0, 1, y1, d};
            case SOUTH -> new double[]{0, y0, 1 - d, 1, y1, 1};
            case WEST -> new double[]{0, y0, 0, d, y1, 1};
            default -> new double[]{1 - d, y0, 0, 1, y1, 1};
        };
    }

    /** A connecting arm from the centre toward side {@code f}: width a..b across it, height y0..y1. */
    private static double[] arm(BlockFace f, double a, double b, double y0, double y1) {
        return switch (f) {
            case NORTH -> new double[]{a, y0, 0, b, y1, 0.5};
            case SOUTH -> new double[]{a, y0, 0.5, b, y1, 1};
            case WEST -> new double[]{0, y0, a, 0.5, y1, b};
            case EAST -> new double[]{0.5, y0, a, 1, y1, b};
            default -> new double[]{a, y0, a, b, y1, b};
        };
    }

    private static BlockFace cw(BlockFace f) {
        return switch (f) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            default -> BlockFace.NORTH;
        };
    }

    private static BlockFace ccw(BlockFace f) {
        return cw(cw(cw(f)));
    }
}

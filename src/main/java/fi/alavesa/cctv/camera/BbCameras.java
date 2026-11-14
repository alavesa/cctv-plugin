package fi.alavesa.cctv.camera;

import fi.alavesa.cctv.api.CameraType;
import fi.alavesa.cctv.bbmodel.AnimationPlayer;
import fi.alavesa.cctv.bbmodel.BbModel;
import fi.alavesa.cctv.bbmodel.BbParser;
import fi.alavesa.cctv.bbmodel.BoneRole;
import fi.alavesa.cctv.bbmodel.CompositeBuilder;
import fi.alavesa.cctv.bbmodel.Mat;
import fi.alavesa.cctv.bbmodel.Rig;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.util.Transformation;
import org.joml.Matrix3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Camera models from Blockbench (.bbmodel) files in plugins/CCTV/models/, converted with the SuperbVehicles pipeline:
 * one item model per moving part, written into the CCTV pack automatically, and one display entity per part at
 * runtime.
 *
 * <p>Conventions (docs/ASSETS.md): the lens point is the Blockbench origin (0, 0, 0) and the lens looks north (-Z).
 * Model it as a FLOOR mount; walls and ceilings are rotated like the built-in brackets. The bone named {@code head}
 * (or {@code lens}) and everything inside it turn with pan and tilt around the origin. Animation clips are chosen by
 * name: {@code idle} (always), {@code move} (while a PTZ head turns), {@code alert}, {@code destroyed}.
 */
public final class BbCameras {

    /** A converted model shared by every camera of the types that use it. */
    public record Model(String file, BbModel model, Rig rig, boolean[] headPart) {
        BbModel.@Nullable Animation clip(String name) {
            for (BbModel.Animation a : model.animations.values()) if (a.name.equalsIgnoreCase(name)) return a;
            return null;
        }
    }

    /** Per-camera animation state (runtime only). */
    static final class State {
        final AnimationPlayer anim = new AnimationPlayer();
        final Map<String, Rig.BoneDelta> deltas = new HashMap<>();
        String clip = "";
    }

    private static final Quaternionf ITEM_FLIP = new Quaternionf(0f, 1f, 0f, 0f);

    private final File dir;
    private final Logger log;
    private final Map<String, Model> byFile = new LinkedHashMap<>();
    private final Map<String, byte[]> packFiles = new LinkedHashMap<>();

    public BbCameras(File dataFolder, Logger log) {
        this.dir = new File(dataFolder, "models");
        this.log = log;
    }

    /** (Re)loads every .bbmodel the camera types reference. Main thread, at startup and on /cctv reload. */
    public void load(Collection<CameraType> types) {
        byFile.clear();
        packFiles.clear();
        dir.mkdirs();
        for (CameraType t : types) {
            String f = t.bbmodel();
            if (f == null || byFile.containsKey(f)) continue;
            File file = new File(dir, f);
            if (!file.isFile()) {
                log.warning("camera type " + t.key() + ": models/" + f + " not found; using the built-in model");
                continue;
            }
            try {
                BbModel m = BbParser.parse(file);
                String path = "bb/" + f.toLowerCase(Locale.ROOT).replaceAll("\\.bbmodel$", "").replaceAll("[^a-z0-9_/.-]", "_");
                CompositeBuilder.Output out = CompositeBuilder.build(m, "cctv", path, Map.of(), List.of(), Map.of(),
                        false, new ArrayList<>(m.animations.keySet()));
                for (String w : out.warnings()) {
                    if (w.contains("no locators")) continue; // vehicle seats/muzzles: not used by cameras
                    log.info("models/" + f + ": " + w);
                }
                Rig rig = out.rig();
                boolean[] head = new boolean[rig.parts.size()];
                boolean anyHead = false;
                for (int i = 0; i < head.length; i++) {
                    for (BbModel.Bone b = rig.parts.get(i).owner; b != null; b = b.parent) {
                        if (BoneRole.of(b.name) == BoneRole.HEAD) {
                            head[i] = true;
                            anyHead = true;
                        }
                    }
                    for (BbModel.Bone b : m.bones.values()) {
                        if (BoneRole.of(b.name) == BoneRole.HEAD && (Math.abs(b.origin[0]) + Math.abs(b.origin[1])
                                + Math.abs(b.origin[2]) > 0.5)) {
                            log.warning("models/" + f + ": bone '" + b.name + "' pivot is " + b.origin[0] + ","
                                    + b.origin[1] + "," + b.origin[2] + "; put it at 0,0,0 (the lens point) or the "
                                    + "head will orbit instead of turning in place");
                            break;
                        }
                    }
                }
                if (!anyHead) log.info("models/" + f + ": no 'head' bone, the whole model stays fixed (no pan/tilt)");
                packFiles.putAll(out.files());
                byFile.put(f, new Model(f, m, rig, head));
                log.info("models/" + f + ": " + out.parts() + " part(s), " + m.animations.size() + " animation(s)");
            } catch (Exception e) {
                log.warning("models/" + f + " could not be read: " + e.getMessage());
            }
        }
    }

    public @Nullable Model model(CameraType t) {
        return t.bbmodel() == null ? null : byFile.get(t.bbmodel());
    }

    /** Files to add to the resource pack (item models + textures of every loaded .bbmodel). */
    public Map<String, byte[]> packFiles() {
        return packFiles;
    }

    /**
     * Display transformations of every part for this camera now; null entries = hidden parts. Base parts follow the
     * mount, head parts the live pan/tilt.
     */
    static Transformation[] pose(Model mdl, Camera c, @Nullable State st) {
        double[][][] M = mdl.rig().matrices(st == null ? null : st.deltas);
        double[][] base = rows(baseMatrix(c));
        double[][] head = rows(new Matrix3d().rotateY(Math.toRadians(-c.yaw())).rotateX(Math.toRadians(c.pitch()))
                .rotateY(Math.PI));
        float s = c.type().modelScale();
        Transformation[] out = new Transformation[M.length];
        for (int i = 0; i < M.length; i++) {
            if (M[i] == null) continue;
            out[i] = transformation(mdl.headPart()[i] ? head : base, s, mdl.rig().parts.get(i), M[i]);
        }
        return out;
    }

    /** Model space -> world for the fixed parts (lens -Z faces the base facing; floor-authored, rotated per mount). */
    private static Matrix3d baseMatrix(Camera c) {
        Matrix3d m = new Matrix3d().rotateY(Math.toRadians(-c.baseYaw()));
        switch (c.mount()) {
            case CEILING -> m.rotateZ(Math.PI);
            case WALL -> m.rotateX(Math.PI / 2);
            default -> { }
        }
        return m.rotateY(Math.PI);
    }

    private static double[][] rows(Matrix3d m) {
        double[][] r = new double[3][3];
        for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++) r[row][col] = m.get(col, row);
        return r;
    }

    /** As SuperbVehicles' VehicleRenderer: translation R*(M*pivot)/16, rotation R*rot(M), scale col(M)/k, flip. */
    private static Transformation transformation(double[][] R, double scale, Rig.Part part, double[][] m) {
        double[] pw = Mat.apply(m, part.pivot);
        double[] tr = Mat.apply3(R, new double[]{pw[0] / 16 * scale, pw[1] / 16 * scale, pw[2] / 16 * scale});
        double[][] m3 = Mat.upper3(m);
        double[] sc = Mat.columnScale(m3);
        double[][] rot = new double[3][3];
        for (int r = 0; r < 3; r++) for (int col = 0; col < 3; col++) rot[r][col] = m3[r][col] / sc[col];
        float[] q = Mat.quaternion(Mat.mul3(R, rot));
        return new Transformation(new Vector3f((float) tr[0], (float) tr[1], (float) tr[2]),
                new Quaternionf(q[0], q[1], q[2], q[3]),
                new Vector3f((float) (sc[0] * scale / part.k), (float) (sc[1] * scale / part.k),
                        (float) (sc[2] * scale / part.k)), new Quaternionf(ITEM_FLIP));
    }

    /** Picks the state clip and advances it by {@code ticks}. */
    static void animate(Model mdl, Camera c, State st, int ticks) {
        String want = c.state() == fi.alavesa.cctv.api.CameraState.DESTROYED ? "destroyed"
                : c.alerting() ? "alert" : c.moving ? "move" : "idle";
        BbModel.Animation clip = mdl.clip(want);
        if (clip == null && !want.equals("idle") && !want.equals("destroyed")) {
            want = "idle";
            clip = mdl.clip("idle");
        }
        st.anim.bind(mdl.model());
        st.anim.state(clip, clip == null ? "" : want, ticks);
        for (Rig.BoneDelta d : st.deltas.values()) d.reset();
        st.anim.tick(mdl.rig(), st.deltas);
        st.clip = want;
    }

    static boolean animated(Model mdl) {
        return !mdl.model().animations.isEmpty();
    }
}

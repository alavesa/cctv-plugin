package fi.alavesa.cctv.bbmodel;

import java.util.Locale;

/**
 * Camera bone roles, read from the bone (group) name in Blockbench, prefix match, case-insensitive:
 * <ul>
 *   <li>{@code head} / {@code lens}: turns with the camera's pan and tilt (its pivot should be the lens point, the
 *       Blockbench origin 0,0,0);</li>
 *   <li>{@code hitbox_*}: not drawn;</li>
 *   <li>anything else: fixed to the bracket (moves only through animation clips).</li>
 * </ul>
 * Adapted from SuperbVehicles' BoneRole (same conventions, camera roles only).
 */
public enum BoneRole {
    HEAD("head", "lens"), HITBOX("hitbox_"), DAMAGE1(), DAMAGE2(), WRECK(), INTACT(), NONE();

    private final String[] prefixes;

    BoneRole(String... prefixes) {
        this.prefixes = prefixes;
    }

    public static BoneRole of(String boneName) {
        if (boneName == null) return NONE;
        String n = boneName.toLowerCase(Locale.ROOT).trim();
        for (BoneRole r : values()) {
            for (String p : r.prefixes) {
                if (n.equals(p) || n.startsWith(p)) return r;
            }
        }
        return NONE;
    }

    /** Roles that need a display part of their own. */
    public boolean animatable() {
        return this == HEAD;
    }
}

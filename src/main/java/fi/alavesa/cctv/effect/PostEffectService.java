package fi.alavesa.cctv.effect;

import java.util.List;
import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;

/**
 * The only place that knows how full-screen post effects reach a client. Mojang has said the post-effect system and
 * the /posteffect command may be redesigned, so every caller goes through this interface and a replacement backend
 * is one class.
 *
 * <p>Rules every backend follows:
 * <ul>
 *   <li>only ids in the {@code cctv} namespace are ever added or removed; effects of other plugins/packs are kept;</li>
 *   <li>never "clear";</li>
 *   <li>the server cannot see whether the client really applied an effect (the client keeps failures to itself), so
 *       callers check the pack status first.</li>
 * </ul>
 */
public interface PostEffectService {

    String NAMESPACE = "cctv";

    /** Backend name for logs and /cctv info. */
    String name();

    /** Whether this backend applies effects through post effects at all (false for the mob-anchor and none backends). */
    boolean appliesEffects();

    /** Replaces the player's cctv effects with {@code ours}, in order. Foreign effects stay where they are. */
    void apply(Player player, List<Key> ours);

    /** Removes every cctv effect from the player. Safe to call when there are none. */
    void removeOurs(Player player);

    static boolean ours(Key key) {
        return NAMESPACE.equals(key.namespace());
    }

    static Key id(String value) {
        return value.indexOf(':') >= 0 ? Key.key(value) : Key.key(NAMESPACE, value);
    }
}

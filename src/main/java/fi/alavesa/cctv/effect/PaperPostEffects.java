package fi.alavesa.cctv.effect;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Primary backend on Minecraft 26.3+: Paper's {@code Player.postEffects()} (PlayerPostEffects), the API behind the
 * vanilla /posteffect command. The whole list is set in one call, so the client gets one packet and the order
 * (zoom, look, signal) is exactly ours.
 *
 * <p>Bound by reflection: the plugin is compiled against the 26.2 API (where it doesn't exist) so the same jar runs
 * on 26.2 and 26.3. {@link #available()} says whether this server has it.
 */
public final class PaperPostEffects implements PostEffectService {

    private static final @Nullable Method POST_EFFECTS;
    private static final @Nullable Method VALUES;
    private static final @Nullable Method SET;

    static {
        Method pe = null, values = null, set = null;
        try {
            pe = Player.class.getMethod("postEffects");
            Class<?> api = Class.forName("io.papermc.paper.entity.PlayerPostEffects");
            values = api.getMethod("values");
            set = api.getMethod("set", java.util.SequencedCollection.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            pe = null;
        }
        POST_EFFECTS = pe;
        VALUES = values;
        SET = set;
    }

    public static boolean available() {
        return POST_EFFECTS != null;
    }

    @Override
    public String name() {
        return "paper";
    }

    @Override
    public boolean appliesEffects() {
        return true;
    }

    @Override
    public void apply(Player player, List<Key> ours) {
        List<Key> current = values(player);
        List<Key> next = new ArrayList<>(current);
        next.removeIf(PostEffectService::ours);
        next.addAll(ours);
        if (!next.equals(current)) set(player, next);
    }

    @Override
    public void removeOurs(Player player) {
        List<Key> current = values(player);
        if (current.stream().noneMatch(PostEffectService::ours)) return;
        List<Key> next = new ArrayList<>(current);
        next.removeIf(PostEffectService::ours);
        set(player, next);
    }

    @SuppressWarnings("unchecked")
    private static List<Key> values(Player p) {
        try {
            return (List<Key>) VALUES.invoke(POST_EFFECTS.invoke(p));
        } catch (ReflectiveOperationException | NullPointerException e) {
            return List.of();
        }
    }

    private static void set(Player p, List<Key> keys) {
        try {
            SET.invoke(POST_EFFECTS.invoke(p), keys);
        } catch (ReflectiveOperationException | NullPointerException e) {
            // nothing sensible to do: the view still works without effects
        }
    }
}

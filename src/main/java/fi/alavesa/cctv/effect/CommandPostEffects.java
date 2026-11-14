package fi.alavesa.cctv.effect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Fallback backend: dispatches the vanilla {@code /posteffect add|remove <player> <id>} command as console, for a
 * server where the Paper API is missing or broken. Only cctv ids are removed (one remove per known id), never "clear".
 * The console will show the command feedback.
 */
public final class CommandPostEffects implements PostEffectService {

    private final Set<Key> knownIds;
    private final Map<UUID, List<Key>> applied = new HashMap<>();

    /** @param knownIds every cctv effect id the plugin may use; removed one by one by {@link #removeOurs}. */
    public CommandPostEffects(Set<Key> knownIds) {
        this.knownIds = knownIds;
    }

    @Override
    public String name() {
        return "command";
    }

    @Override
    public boolean appliesEffects() {
        return true;
    }

    @Override
    public void apply(Player player, List<Key> ours) {
        List<Key> before = applied.getOrDefault(player.getUniqueId(), List.of());
        if (before.equals(ours)) return;
        // remove all first so the order of the new list is preserved (add appends)
        for (Key k : before) run("posteffect remove " + player.getName() + " " + k.asString());
        for (Key k : ours) run("posteffect add " + player.getName() + " " + k.asString());
        applied.put(player.getUniqueId(), new ArrayList<>(ours));
    }

    @Override
    public void removeOurs(Player player) {
        List<Key> before = applied.remove(player.getUniqueId());
        // after a restart we don't know what the player has, so remove every id we might have added
        for (Key k : before != null ? before : List.copyOf(knownIds)) {
            run("posteffect remove " + player.getName() + " " + k.asString());
        }
    }

    private static void run(String command) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }
}

package fi.alavesa.cctv.api;

import org.bukkit.entity.Player;

/**
 * Decides whether a player may view a camera of a given clearance level. Register your own (keycards, LuckPerms meta,
 * an SCP framework) with {@link CctvApi#setClearanceProvider}. The default is permission based: level 0 is open to
 * everyone, level N needs cctv.clearance.N or any higher level.
 */
@FunctionalInterface
public interface ClearanceProvider {

    boolean hasClearance(Player player, int level);
}

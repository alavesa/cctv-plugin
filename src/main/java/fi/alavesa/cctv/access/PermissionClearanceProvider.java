package fi.alavesa.cctv.access;

import fi.alavesa.cctv.api.ClearanceProvider;
import org.bukkit.entity.Player;

/** Default clearance: level 0 is open, level N needs cctv.clearance.N or any higher level up to {@code maxLevel}. */
public final class PermissionClearanceProvider implements ClearanceProvider {

    private final int maxLevel;

    public PermissionClearanceProvider(int maxLevel) {
        this.maxLevel = maxLevel;
    }

    @Override
    public boolean hasClearance(Player player, int level) {
        if (level <= 0 || player.hasPermission("cctv.clearance.all")) return true;
        for (int l = level; l <= maxLevel; l++) {
            if (player.hasPermission("cctv.clearance." + l)) return true;
        }
        return false;
    }
}

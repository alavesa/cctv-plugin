package fi.alavesa.cctv.view;

import org.bukkit.entity.Player;

/**
 * On-screen overlay while viewing. Phase 3 ships {@link SimpleHud} (plain subtitle); Phase 4 replaces it with the
 * font-glyph CCTV HUD without touching the session logic.
 */
public interface ViewHud {

    void start(ViewSession session, Player player);

    /** Called every tick; implementations throttle themselves. */
    void tick(ViewSession session, Player player);

    void switched(ViewSession session, Player player);

    void signalLost(ViewSession session, Player player);

    void stop(ViewSession session, Player player);
}

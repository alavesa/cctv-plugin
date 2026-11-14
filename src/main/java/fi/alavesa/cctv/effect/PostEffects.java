package fi.alavesa.cctv.effect;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;

/** Effect ids shipped in the pack, and backend selection from config (effects.backend). */
public final class PostEffects {

    public static final Key STATIC_BURST = PostEffectService.id("static_burst");
    public static final Key SIGNAL_LOST = PostEffectService.id("signal_lost");
    public static final Key SIGNAL_WEAK = PostEffectService.id("signal_weak");
    public static final Key SIGNAL_CRITICAL = PostEffectService.id("signal_critical");

    private PostEffects() {
    }

    /** cctv:zoom_2x etc. Only 2x, 3x and 4x ship in the pack; other levels need their own post_effect file. */
    public static Key zoom(float level) {
        return PostEffectService.id("zoom_" + Math.round(level) + "x");
    }

    /** Look id from a camera type's "look" value: a bare name is cctv:&lt;name&gt;, "ns:name" is used as is. */
    public static Key look(String look) {
        return PostEffectService.id(look);
    }

    public static Set<Key> shipped() {
        Set<Key> s = new LinkedHashSet<>();
        for (String n : List.of("standard", "night_vision", "thermal", "signal_weak", "signal_critical",
                "static_burst", "signal_lost", "zoom_2x", "zoom_3x", "zoom_4x")) {
            s.add(PostEffectService.id(n));
        }
        return s;
    }

    public static PostEffectService create(String backend, Logger log) {
        return switch (backend) {
            case "paper" -> new PaperPostEffects();
            case "command" -> new CommandPostEffects(shipped());
            case "mob" -> new NoPostEffects("mob");
            case "none" -> new NoPostEffects("none");
            default -> {
                if (paperApiPresent()) yield new PaperPostEffects();
                log.warning("Player.postEffects() is not available on this server; falling back to /posteffect");
                yield new CommandPostEffects(shipped());
            }
        };
    }

    private static boolean paperApiPresent() {
        try {
            Player.class.getMethod("postEffects");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}

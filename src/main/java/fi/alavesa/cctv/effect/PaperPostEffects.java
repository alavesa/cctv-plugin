package fi.alavesa.cctv.effect;

import io.papermc.paper.entity.PlayerPostEffects;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;

/**
 * Primary backend: Paper's {@code Player.postEffects()} (26.3), the API behind the vanilla /posteffect command
 * ({@code ClientboundPostEffectsPacket}). The whole list is set in one call, so the client gets one packet and the
 * order (zoom, look, signal) is exactly ours.
 */
public final class PaperPostEffects implements PostEffectService {

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
        PlayerPostEffects fx = player.postEffects();
        List<Key> next = new ArrayList<>(fx.values());
        next.removeIf(PostEffectService::ours);
        next.addAll(ours);
        if (!next.equals(fx.values())) fx.set(next);
    }

    @Override
    public void removeOurs(Player player) {
        PlayerPostEffects fx = player.postEffects();
        List<Key> current = fx.values();
        if (current.stream().noneMatch(PostEffectService::ours)) return;
        List<Key> next = new ArrayList<>(current);
        next.removeIf(PostEffectService::ours);
        fx.set(next);
    }
}

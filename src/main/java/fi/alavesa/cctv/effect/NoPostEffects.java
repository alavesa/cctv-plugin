package fi.alavesa.cctv.effect;

import java.util.List;
import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;

/**
 * Backends that send no post effects. "mob": the look comes from the vanilla spectated-entity shader instead (the
 * anchor is a creeper, spider or enderman; see the view package). "none": overlay/HUD only.
 */
public final class NoPostEffects implements PostEffectService {

    private final String name;

    public NoPostEffects(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean appliesEffects() {
        return false;
    }

    @Override
    public void apply(Player player, List<Key> ours) {
    }

    @Override
    public void removeOurs(Player player) {
    }
}

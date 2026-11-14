package fi.alavesa.cctv.config;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * MiniMessage strings from the messages section of config.yml. A missing key falls back to the key itself, so a
 * typo is visible in game instead of silently sending nothing.
 */
public final class Messages {

    private final FileConfiguration config;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public Messages(FileConfiguration config) {
        this.config = config;
    }

    public Component get(String key, TagResolver... resolvers) {
        String raw = config.getString("messages." + key);
        if (raw == null) return Component.text(key);
        String prefix = config.getString("messages.prefix", "");
        return mm.deserialize(raw.replace("<prefix>", prefix), resolvers);
    }

    public void send(Audience to, String key, TagResolver... resolvers) {
        to.sendMessage(get(key, resolvers));
    }

    /** Unparsed text placeholder: player-supplied names can never inject tags. */
    public static TagResolver p(String name, Object value) {
        return Placeholder.unparsed(name, String.valueOf(value));
    }

    public static TagResolver c(String name, Component value) {
        return Placeholder.component(name, value);
    }
}

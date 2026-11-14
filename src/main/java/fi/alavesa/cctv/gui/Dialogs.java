package fi.alavesa.cctv.gui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** One-field text prompts with the native dialog screen (Paper 26.3 dialog API). */
public final class Dialogs {

    private Dialogs() {
    }

    /** Shows a text prompt; {@code onDone} runs on the main thread with the entered text (never null). */
    public static void text(Plugin plugin, Player p, Component title, Component label, String initial, int maxLength,
                            Consumer<String> onDone) {
        Dialog d = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(title)
                        .canCloseWithEscape(true)
                        .inputs(List.of(DialogInput.text("value", label).initial(initial).maxLength(maxLength)
                                .width(256).build()))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.create(Component.text("OK"), null, 100, DialogAction.customClick(
                                (view, audience) -> {
                                    String v = view.getText("value");
                                    Bukkit.getScheduler().runTask(plugin, () -> onDone.accept(v == null ? "" : v.trim()));
                                },
                                ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build())),
                        ActionButton.create(Component.text("Cancel"), null, 100, null))));
        p.showDialog(d);
    }
}

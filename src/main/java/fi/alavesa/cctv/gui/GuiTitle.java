package fi.alavesa.cctv.gui;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;

/**
 * Menu titles with the CCTV background. The title label is drawn at (8, 6) inside the chest window, on top of the
 * window's own texture, so a 176x222 glyph (cctv:gui U+E100, ascent 13) moved 8 px left covers the whole 6-row window.
 * The cursor is then moved back to x = 8 for the real title text. Without the pack the glyph is a missing-character
 * box, so {@code textured=false} gives a plain title.
 */
public final class GuiTitle {

    private static final Key GUI = Key.key("cctv", "gui");
    private static final Key SPACE = Key.key("cctv", "space");
    private static final TextColor TEXT = TextColor.color(0x7CFF9A);

    private GuiTitle() {
    }

    public static Component of(String text, boolean textured) {
        if (!textured) return Component.text(text, NamedTextColor.DARK_GRAY);
        return Component.text()
                // -8: U+F103 (-8)
                .append(Component.text("", Style.style().font(SPACE).build()))
                .append(Component.text("", Style.style(NamedTextColor.WHITE).font(GUI)))
                // background advance is 177; back to x = 8 is -169 = -128 -32 -8 -1
                .append(Component.text("", Style.style().font(SPACE).build()))
                .append(Component.text(text, TEXT))
                .build();
    }
}

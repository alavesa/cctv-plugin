# CCTV HUD and the `core/text` override

## What the player sees

The on-screen display is drawn with CCTV fonts in the **subtitle** line of a title (refreshed every second, no fade).
That line is laid out at exact positions relative to the screen centre:

```
 ● REC                              2026-11-14     ← REC dot blinks (shader, no server traffic)
 CAM-4                                13:17:23
 YARD PTZ                          ▶ CAM-4         ← camera list: current amber, offline dimmed with X
 SIGNAL WEAK (when damaged)          CAM-5
                                     ...
          A/D PAN  W/S TILT  CTRL+A/D CAM  SHIFT EXIT   (first view.help-seconds)
 NET TEST                                   2X     ← zoom
 SIG ▂▄▆█                                          ← bars from camera health
```

Full-screen cards (double-size text on a black panel): **NO SIGNAL** (feed lost), **CAMERA OFFLINE**, **POWER
FAILURE**, **ACCESS DENIED** (refused view). Corner brackets, vignette and scanlines over the picture come from the
post effect, which knows the real screen size.

Players without the CCTV pack get a plain-text subtitle instead (the CCTV fonts would show as missing glyphs).

## Why the subtitle

| Channel | Verdict |
|---|---|
| Action bar | No: on the SCP server it's the shared Labra `ActionBars` line, and other plugins overwrite it every tick. |
| Boss bar | No: the bar sprite always draws, and hiding it would mean overriding the vanilla boss-bar sprites for everyone. |
| Title | The main title is left empty (it's 4× scale). |
| **Subtitle** | Yes: isolated, 2× scale (1 font px = 2 GUI px), stays up with `fadeIn 0`. Another plugin's title can replace it until the next refresh (≤ 1 s). |

## How positioning works

Verified against the 26.3 client (`BitmapProvider`):

- A bitmap glyph's advance is `round(width × height / rows) + 1`, and its width is measured up to the last
  non-transparent column.
- `ascent` may not exceed `height`.

So:

1. **Rows.** One sheet, `textures/font/osd.png`: 16×7 cells of 6×64 px, glyph pixels in the top 8 rows of each cell.
   Fonts `cctv:hud_0 … hud_13` use that sheet with `height` 64 and `ascent` 64, 55, 46, … (9 px steps). The tall
   cell is what lets a row sit above the subtitle line. `cctv:hud_big_0 … 5` are the same sheet at `height` 128
   (2× glyphs) for the cards.
2. **Columns.** Each cell has one alpha-1 pixel in its last column, so every glyph is exactly 6 px wide and advances
   7 px (13 at 2×). The plugin can compute every width. `cctv:space` provides signed spaces of ±2ⁿ and ±0.5 px
   (space advances are floats in 26.3), so the cursor can be moved anywhere.
3. **Centring.** Every line ends with the cursor moved back to 0. The client centres the subtitle by its total width
   (now 0), so x = 0 is exactly the screen centre.
4. The box is `hud.half-width` font px each side of the centre (default 100 = 200 GUI px). On narrow windows or at a
   big GUI scale, lower it.

Text is upper-cased; characters the font doesn't have become `?`. The help line names the default keys (A/D, W/S,
Ctrl, Space, Shift): a keybind component would show remapped keys, but its width can't be known server-side, so it
would break the layout. The plain-subtitle fallback does use keybinds.

## The marker, and why it can't collide

The HUD effects (flicker, scanlines, chromatic fringes, blink, glitch offsets) live in the vanilla text shader
`assets/minecraft/shaders/core/text.vsh/fsh` (26.3 renamed `rendertype_text` to this). A pixel gets CCTV treatment
only if **all three** hold:

1. **`IS_GUI` is defined.** The shader program is compiled separately for GUI text (chat, titles, tooltips) and world
   text (signs, name tags, text displays, item-frame maps). World text never reaches the HUD code.
2. **The text colour is `#0CC7xx`** (red 12, green 199). The blue byte is data: bits 0–4 are flags (1 flicker,
   2 glitch, 4 aberration, 8 scanlines, 16 blink), bits 5–7 a palette entry (white, red, green, amber, grey, cyan,
   dark grey, black). Text is sent with shadow colour "none", so no shadow copies exist.
3. **The glyph texel has alpha exactly 254.** Every visible pixel of the CCTV font sheet uses alpha 254. Vanilla font
   textures (`ascii.png`, unifont) only contain alpha 0 and 255.

So a chat message, sign or name tag in exactly `#0CC7xx` still renders as vanilla: gate 3 fails, and the shader
returns the colour vanilla computed. The one thing the colour alone can trigger is the **vertex** glitch offset,
because the vertex stage can't read texels: during a glitch burst (about one every 4 s, 3 GUI px) a GUI line typed in
exactly `#0CC7` + a blue value with bit 1 set would jitter sideways. That's 128 of 16.7 million colours, and the
result is cosmetic only.

Other packs' fonts would also need alpha-254 pixels **and** that exact colour to be affected.

## If another pack also overrides `core/text.vsh` / `text.fsh`

Resource packs stack by file: if two packs both ship `assets/minecraft/shaders/core/text.fsh`, only the
higher-priority one is used. The other's changes are silently lost. To combine them, edit the winning pack's copy:

1. Copy `assets/cctv/shaders/include/cctv_text_vertex.glsl` and `cctv_text_fragment.glsl` along with everything
   else under `assets/cctv/`. They're self-contained and namespaced.
2. In the other pack's `text.vsh`, after its `#include` lines, paste:
   ```glsl
   // ===== CCTV BEGIN =====
   #include <cctv:cctv_text_vertex.glsl>
   // ===== CCTV END =====
   ```
   and directly after the line that writes `gl_Position`:
   ```glsl
       // ===== CCTV BEGIN =====
       gl_Position = ProjMat * ModelViewMat * vec4(Position + cctv_gui_offset(Position, Color), 1.0);
       // ===== CCTV END =====
   ```
   If that pack moves vertices itself, add `cctv_gui_offset(Position, Color)` to the position it uses instead of
   replacing its line.
3. In its `text.fsh`, after its `#include` lines:
   ```glsl
   // ===== CCTV BEGIN =====
   #include <cctv:cctv_text_fragment.glsl>
   // ===== CCTV END =====
   ```
   and right after the line computing the final `color` (before `if (color.a < 0.1) discard;`):
   ```glsl
       // ===== CCTV BEGIN =====
       color = cctv_text_fragment(color, texColor, vertexColor, Sampler0, texCoord0, ColorModulator);
       // ===== CCTV END =====
   ```
   If that pack renamed `texColor`, `vertexColor`, `Sampler0` or `texCoord0`, pass its names.
4. Both includes guard themselves (`#ifndef`) and only add functions and constants prefixed `cctv_` / `CCTV_`, so
   they don't clash with the other pack's code. The only vertex output used is `gl_Position`; no extra varyings are
   added, so `layout(location = …)` slots can't collide.

`tools/merge_pack.py` does this automatically when the other pack's file still contains the vanilla anchor lines
(see [PACK.md](PACK.md)).

Validate after editing: `python3 tools/validate_shaders.py <glslang> <assets roots> text.vsh text.fsh -DIS_GUI`,
then check in game (F3+T reloads packs). If the client can't compile the shader, it reports the error in its log
(`logs/latest.log`). What the client does next with a broken core shader (fall back to vanilla, reject the pack, or
render text wrongly) is not verified, so always validate before shipping.

## Tuning

- Glitch: `CCTV_GLITCH_*` in `cctv_text_vertex.glsl`.
- Flicker, scanlines, blink, fringes, palette: `CCTV_*` constants and `cctv_palette()` in `cctv_text_fragment.glsl`.
- Layout: `hud.half-width`, `hud.date-format`, `hud.time-format` in config.yml. Which flags each element uses is in
  `CctvHud.java`.
- Fonts: edit glyph art in `tools/gen_hud_font.py` and run it.

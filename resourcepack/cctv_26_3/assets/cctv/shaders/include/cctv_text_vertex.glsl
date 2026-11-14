#ifndef CCTV_TEXT_VERTEX_GLSL
#define CCTV_TEXT_VERTEX_GLSL

// =====================================================================================================================
// CCTV: vertex part of the core/text override (HUD glitch offsets).
//
// MARKER: CCTV HUD text is sent with text colour #0CC7xx (red 12, green 199). The blue byte carries flags:
//   bits 0-4  flags   1 flicker | 2 glitch | 4 aberration | 8 scanlines | 16 blink
//   bits 5-7  palette 0 white | 1 red | 2 green | 3 amber | 4 grey | 5 cyan | 6 dark grey | 7 black
// Only GUI text (IS_GUI: title, subtitle, chat, ...) is ever looked at. World text (signs, name tags, text displays)
// is compiled without IS_GUI and never reaches this code. The fragment part additionally requires the glyph texel to
// have alpha 254, which only CCTV font sheets use (see cctv_text_fragment.glsl). This vertex part can only check
// the colour, so a chat line typed in exactly #0CC7xx with the glitch bit set could jitter during a glitch burst.
// That is the only effect such a coincidence can have; its colour and pixels stay vanilla.
// =====================================================================================================================

#include <minecraft:globals.glsl>

const float CCTV_GLITCH_SLOTS = 5.0;    // how often the "HUD glitch burst?" dice is rolled, per second
const float CCTV_GLITCH_CHANCE = 0.05;  // chance per roll (0.05 = roughly one burst every 4 s)
const float CCTV_GLITCH_PX = 3.0;       // max sideways shift of a text row during a burst, GUI pixels
const float CCTV_GLITCH_ROW_PX = 4.0;   // height of a row that shifts as one piece, GUI pixels

#ifndef CCTV_HASH_DEFINED
#define CCTV_HASH_DEFINED
float cctv_hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}
#endif

// Offset to add to Position (GUI space) for CCTV HUD glyphs; zero for everything else.
vec3 cctv_gui_offset(vec3 position, vec4 color) {
#ifdef IS_GUI
    ivec3 c = ivec3(round(color.rgb * 255.0));
    if (c.r != 12 || c.g != 199 || (c.b & 2) == 0) return vec3(0.0);
    float t = GameTime * 1200.0;
    float slot = floor(t * CCTV_GLITCH_SLOTS);
    float burst = step(1.0 - CCTV_GLITCH_CHANCE, cctv_hash(vec2(slot, 11.0)));
    float row = floor(position.y / CCTV_GLITCH_ROW_PX);
    return vec3((cctv_hash(vec2(row, slot)) - 0.5) * 2.0 * CCTV_GLITCH_PX * burst, 0.0, 0.0);
#else
    return vec3(0.0);
#endif
}

// MONITOR SCREENS (non-GUI): item-frame maps of an invisible frame lie in the same plane as the block face behind
// them (and as the desk TV's back panel), so the two depth-fight and the wall "randomly" shows through the picture.
// CCTV screens (the same corner sentinel as in cctv_text_fragment.glsl) are pulled toward the viewer by
// CCTV_SCREEN_PULL blocks in view space, which wins the depth test at any distance up to ~150 blocks. Other maps and
// all other text are untouched.
const float CCTV_SCREEN_PULL = 0.03;

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
uniform sampler2D Sampler0;

bool cctv_v_rgb(int x, int y, int rgb) {
    ivec3 c = ivec3(round(texelFetch(Sampler0, ivec2(x, y), 0).rgb * 255.0));
    return c == ivec3((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255);
}

vec4 cctv_screen_pull(vec4 view) {
    if (textureSize(Sampler0, 0) != ivec2(128, 128)) return view;
    bool rows0 = cctv_v_rgb(0, 0, 0x5E2872) && cctv_v_rgb(127, 0, 0x307370);
    bool rows1 = cctv_v_rgb(0, 127, 0x5E2872) && cctv_v_rgb(127, 127, 0x307370);
    if (!rows0 && !rows1) return view;
    float d = length(view.xyz);
    if (d < 0.001) return view;
    view.xyz *= max(0.0, 1.0 - CCTV_SCREEN_PULL / d);
    return view;
}
#else
vec4 cctv_screen_pull(vec4 view) {
    return view;
}
#endif

#endif

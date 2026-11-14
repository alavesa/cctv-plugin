#ifndef CCTV_TEXT_FRAGMENT_GLSL
#define CCTV_TEXT_FRAGMENT_GLSL

// =====================================================================================================================
// CCTV: fragment part of the core/text override.
//
// HUD glyphs (IS_GUI). All three gates must pass, otherwise the colour vanilla computed is returned untouched:
//   1. IS_GUI is defined (GUI text only; world text never qualifies),
//   2. the vertex colour is #0CC7xx (see cctv_text_vertex.glsl for the bit layout),
//   3. the glyph texel's alpha is exactly 254. CCTV font sheets use alpha 254 for every visible pixel; vanilla font
//      textures (ascii.png, unifont) only use 0 and 255, so a vanilla glyph can never pass even in a matching colour.
// When they pass, the glyph's own texture colours are kept (red REC dot stays red), tinted by the palette entry, and
// the flagged effects are applied, animated with GameTime.
//
// MONITOR SCREENS (non-GUI). Item-frame maps are drawn with this same shader (RenderTypes.text, one 128x128
// texture per map; verified in the 26.3 client). A map gets the CRT treatment only if its texture is 128x128 AND
// its four corner pixels carry the CCTV sentinel colours (see Screen.java): magenta, diamond, gold and pink, all
// in their darkest shade, in that order. Ordinary maps, held or hung, never carry that arrangement, so they draw
// exactly as vanilla. Pixel (1,0) is the screen state, (2,0) the style and (3,0) the panel: flat (LCD, the default
// monitor) or CRT. Everything except the LIVE picture is
// drawn here procedurally (colour bars, static, boot sweep, off), so those states cost the server nothing.
// =====================================================================================================================

#moj_import <minecraft:globals.glsl>

#ifndef CCTV_HASH_DEFINED
#define CCTV_HASH_DEFINED
float cctv_hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}
#endif

const float CCTV_FLICKER_HZ = 14.0;     // HUD brightness flicker steps per second
const float CCTV_FLICKER = 0.18;        // max brightness lost in a flicker step
const float CCTV_SCAN_DARK = 0.72;      // brightness of every other screen row on scanline-flagged text
const float CCTV_BLINK_HZ = 1.0;        // blink rate of blink-flagged glyphs (REC dot)
const float CCTV_FRINGE_ALPHA = 0.55;   // opacity of the chromatic-aberration fringes
const vec3 CCTV_FRINGE_RIGHT = vec3(1.0, 0.25, 0.2); // fringe colour drawn right of a glyph pixel
const vec3 CCTV_FRINGE_LEFT = vec3(0.2, 0.85, 1.0);  // fringe colour drawn left of a glyph pixel

// ---- CRT tuning ----------------------------------------------------------------------------------------------------
const float CRT_CURVE = 0.10;           // barrel bulge of the picture inside each tile (0 = flat glass)
const float CRT_SCANLINE = 0.32;        // darkness between map-pixel rows
const float CRT_MASK = 0.16;            // strength of the RGB phosphor stripe mask
const float CRT_NOISE = 0.07;           // animated noise on the picture
const float CRT_FLICKER = 0.05;         // brightness flicker depth
const float CRT_FLICKER_HZ = 18.0;      // flicker steps per second
const float CRT_ROLL = 0.07;            // brightness of the slow rolling bar
const float CRT_ROLL_SPEED = 0.12;      // rolling bar speed, screens per second
const float CRT_VIGNETTE = 0.55;        // darkening toward the tile corners
const float CRT_EDGE_GLOW = 0.30;       // bluish glow just inside the bezel
const float CRT_GAIN = 1.10;            // overall brightness; screens light themselves (world light is ignored)
const float CRT_SOFT = 0.55;            // blend toward a 5-tap blur: smooths the server's dither pattern into tone
                                        // (0 = razor-sharp map pixels, 1 = fully blurred)
const float CRT_BOOT_SECONDS = 2.0;     // length of one boot-sweep loop
// ---- flat panel (LCD) tuning: nothing here varies across a tile, so a multi-tile wall reads as ONE screen ---------
const float FLAT_SOFT = 0.40;           // dither smoothing, as CRT_SOFT
const float FLAT_CONTRAST = 1.08;       // LCD punch around mid grey
const float FLAT_GAIN = 1.04;
const vec3  FLAT_NO_SIGNAL = vec3(0.04, 0.10, 0.42); // TV-style blue "no signal" background
const float FLAT_BLOCK = 8.0;           // macroblock size of the digital break-up (STATIC), map pixels
const vec3  CRT_GREEN = vec3(0.30, 1.00, 0.40);  // green phosphor (style 1, night-vision cameras)
const vec3  CRT_AMBER = vec3(1.00, 0.70, 0.22);  // amber phosphor (style 2)

vec3 cctv_palette(int i) {
    if (i == 0) return vec3(0.95, 0.97, 0.95); // white
    if (i == 1) return vec3(1.00, 0.27, 0.22); // red
    if (i == 2) return vec3(0.45, 1.00, 0.50); // green
    if (i == 3) return vec3(1.00, 0.76, 0.25); // amber
    if (i == 4) return vec3(0.62, 0.64, 0.62); // grey
    if (i == 5) return vec3(0.45, 0.90, 1.00); // cyan
    if (i == 6) return vec3(0.28, 0.29, 0.28); // dark grey
    return vec3(0.0);                          // black
}

bool cctv_glyph_texel(float alpha) {
    return abs(alpha * 255.0 - 254.0) < 0.5;
}


// ---- monitor screens ------------------------------------------------------------------------------------------------

ivec3 cctv_px(sampler2D tex, int x, int y) {
    return ivec3(round(texelFetch(tex, ivec2(x, y), 0).rgb * 255.0));
}

bool cctv_rgb(ivec3 c, int rgb) {
    return c == ivec3((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255);
}

// 0 = not a CCTV screen, 1 = sentinel in texture row 0, 2 = sentinel in row 127 (rows uploaded flipped)
int cctv_screen_row(sampler2D tex) {
    if (textureSize(tex, 0) != ivec2(128, 128)) return 0;
    if (cctv_rgb(cctv_px(tex, 0, 0), 0x5E2872) && cctv_rgb(cctv_px(tex, 127, 0), 0x307370)
            && cctv_rgb(cctv_px(tex, 0, 127), 0x847E28) && cctv_rgb(cctv_px(tex, 127, 127), 0x804357)) return 1;
    if (cctv_rgb(cctv_px(tex, 0, 127), 0x5E2872) && cctv_rgb(cctv_px(tex, 127, 127), 0x307370)
            && cctv_rgb(cctv_px(tex, 0, 0), 0x847E28) && cctv_rgb(cctv_px(tex, 127, 0), 0x804357)) return 2;
    return 0;
}

vec3 cctv_thermal(float x) {
    vec3 c = mix(vec3(0.0, 0.0, 0.05), vec3(0.10, 0.10, 0.80), smoothstep(0.00, 0.20, x));
    c = mix(c, vec3(0.60, 0.05, 0.70), smoothstep(0.20, 0.40, x));
    c = mix(c, vec3(0.95, 0.10, 0.10), smoothstep(0.40, 0.60, x));
    c = mix(c, vec3(1.00, 0.55, 0.00), smoothstep(0.60, 0.78, x));
    c = mix(c, vec3(1.00, 0.95, 0.20), smoothstep(0.78, 0.92, x));
    return mix(c, vec3(1.0), smoothstep(0.92, 1.00, x));
}

vec3 cctv_bars(float x) {
    // SMPTE-style test card: white, yellow, cyan, green, magenta, red, blue
    int i = int(clamp(x * 7.0, 0.0, 6.0));
    if (i == 0) return vec3(0.75);
    if (i == 1) return vec3(0.75, 0.75, 0.0);
    if (i == 2) return vec3(0.0, 0.75, 0.75);
    if (i == 3) return vec3(0.0, 0.75, 0.0);
    if (i == 4) return vec3(0.75, 0.0, 0.75);
    if (i == 5) return vec3(0.75, 0.0, 0.0);
    return vec3(0.0, 0.0, 0.75);
}

vec3 cctv_style(vec3 col, ivec3 style) {
    float luma = dot(col, vec3(0.299, 0.587, 0.114));
    if (cctv_rgb(style, 0xDCDCDC)) return CRT_GREEN * luma * 1.25;
    if (cctv_rgb(style, 0xB4B4B4)) return CRT_AMBER * luma * 1.25;
    if (cctv_rgb(style, 0x878787)) return cctv_thermal(clamp(luma * 1.2, 0.0, 1.0));
    return col;
}

vec3 cctv_soft(sampler2D tex, ivec2 p, float amount) {
    vec3 img = texelFetch(tex, p, 0).rgb;
    if (amount <= 0.0) return img;
    vec3 blur = img * 0.4
            + texelFetch(tex, clamp(p + ivec2(1, 0), ivec2(1), ivec2(126)), 0).rgb * 0.15
            + texelFetch(tex, clamp(p - ivec2(1, 0), ivec2(1), ivec2(126)), 0).rgb * 0.15
            + texelFetch(tex, clamp(p + ivec2(0, 1), ivec2(1), ivec2(126)), 0).rgb * 0.15
            + texelFetch(tex, clamp(p - ivec2(0, 1), ivec2(1), ivec2(126)), 0).rgb * 0.15;
    return mix(img, blur, amount);
}

// The blue "no signal" screen of a flat TV, with a faint drifting shimmer so it reads as a lit panel.
vec3 cctv_flat_blue(ivec2 p, float t) {
    return FLAT_NO_SIGNAL * (0.94 + 0.06 * cctv_hash(vec2(p / 4) + floor(t * 6.0) * vec2(3.0, 5.0)));
}

// Flat panel: square pixels, no curve/scanlines/roll/vignette, self-lit, blue no-signal screen, digital break-up.
// The server's text frames (STANDBY, CAMERA OFFLINE, POWER FAILURE, BOOTING) have a dark background (map colour
// 0x0D0D0D, Screen.BACKGROUND); on a TV that background is the blue screen and the text stays on top.
vec4 cctv_flat(sampler2D tex, vec2 uv, ivec3 state, ivec3 style, float t) {
    if (cctv_rgb(state, 0x111111)) return vec4(vec3(0.012, 0.012, 0.016), 1.0); // OFF: black glass
    ivec2 p = clamp(ivec2(uv * 128.0), ivec2(1), ivec2(126));
    vec3 img = cctv_soft(tex, p, FLAT_SOFT);
    float textMask = step(0.6, dot(img, vec3(0.333)));
    bool bg = cctv_rgb(cctv_px(tex, p.x, p.y), 0x0D0D0D);
    vec3 col;
    if (cctv_rgb(state, 0xB4B4B4)) {
        col = mix(cctv_flat_blue(p, t), vec3(1.0), textMask);     // NO SIGNAL
    } else if (cctv_rgb(state, 0x878787)) {
        col = bg ? cctv_flat_blue(p, t) : img;                      // OFFLINE: blue screen + the server's text
    } else if (cctv_rgb(state, 0x191919)) {
        float phase = fract(t / CRT_BOOT_SECONDS);                  // BOOT: backlight up, then the blue screen
        col = vec3(0.05, 0.05, 0.07) * smoothstep(0.0, 0.3, phase)
                + (bg ? cctv_flat_blue(p, t) : img) * smoothstep(0.45, 0.8, phase);
    } else if (cctv_rgb(state, 0xDCDCDC) && bg) {
        col = cctv_flat_blue(p, t);                                 // STANDBY: blue screen + the server's text
    } else if (cctv_rgb(state, 0xDCDCDC)) {
        // STATIC: frozen macroblocks of the last picture, some smeared, some dropped to grey noise
        ivec2 b = ivec2(floor(vec2(p) / FLAT_BLOCK));
        float step_t = floor(t * 12.0);
        float h = cctv_hash(vec2(b) + step_t * vec2(13.0, 7.0));
        ivec2 q = clamp(b * int(FLAT_BLOCK) + ivec2(int(FLAT_BLOCK) / 2), ivec2(1), ivec2(126));
        vec3 blk = texelFetch(tex, q, 0).rgb;
        col = h < 0.35 ? blk : h < 0.55 ? vec3(cctv_hash(vec2(p) + step_t)) * 0.6 : img;
    } else {
        col = img;                                                  // LIVE
    }
    col = cctv_style(col, style);
    col = (col - 0.5) * FLAT_CONTRAST + 0.5;
    return vec4(clamp(col * FLAT_GAIN, 0.0, 1.0), 1.0);
}

vec4 cctv_crt(vec4 color, sampler2D tex, vec2 uv) {
    int row = cctv_screen_row(tex);
    if (row == 0) return color;
    int sy = row == 1 ? 0 : 127;
    ivec3 state = cctv_px(tex, 1, sy);
    ivec3 style = cctv_px(tex, 2, sy);
    float t = GameTime * 1200.0;
    if (cctv_rgb(cctv_px(tex, 3, sy), 0xB4B4B4)) return cctv_flat(tex, uv, state, style, t);

    if (cctv_rgb(state, 0x111111)) {
        // OFF: dark glass with a faint diagonal reflection
        return vec4(vec3(0.015) + vec3(0.03) * smoothstep(0.3, 0.0, abs(uv.x - uv.y - 0.2)), 1.0);
    }

    vec2 c = uv - 0.5;
    vec2 cu = 0.5 + c * (1.0 + CRT_CURVE * dot(c, c) * 4.0);
    if (cu.x < 0.0 || cu.x > 1.0 || cu.y < 0.0 || cu.y > 1.0) return vec4(0.01, 0.01, 0.012, 1.0);
    // clamp away from the outermost ring of pixels: that is where the sentinel lives
    ivec2 p = clamp(ivec2(cu * 128.0), ivec2(1), ivec2(126));
    vec3 img = cctv_soft(tex, p, CRT_SOFT);
    float n = cctv_hash(vec2(p) + fract(t * 37.0) * vec2(91.0, 57.0));
    float textMask = step(0.6, dot(img, vec3(0.333)));
    vec3 col;
    if (cctv_rgb(state, 0xB4B4B4)) {
        // NO SIGNAL: colour bars, darker strip at the bottom, the server's text on top
        col = cu.y > 0.78 ? cctv_bars(1.0 - cu.x) * 0.35 : cctv_bars(cu.x);
        col = mix(col, vec3(1.0), textMask);
    } else if (cctv_rgb(state, 0x878787)) {
        // OFFLINE: the server's dark text frame with light snow
        col = img + vec3(n * 0.12);
    } else if (cctv_rgb(state, 0x191919)) {
        // BOOT: a bright line opening from the centre, then the text
        float phase = fract(t / CRT_BOOT_SECONDS);
        float open = smoothstep(0.0, 0.6, phase);
        float band = smoothstep(open * 0.5 + 0.01, open * 0.5, abs(cu.y - 0.5));
        col = mix(vec3(0.0), vec3(0.6, 0.7, 0.8), band * (1.0 - open * 0.7)) + img * step(0.6, phase);
    } else if (cctv_rgb(state, 0xDCDCDC)) {
        // STATIC: snow over the last picture
        col = mix(img, vec3(n), 0.8);
    } else {
        col = img; // LIVE
    }

    col = cctv_style(col, style);

    float scan = 0.5 + 0.5 * cos(fract(cu.y * 128.0) * 6.2831853);
    col *= mix(1.0, scan, CRT_SCANLINE);
    int sub = int(mod(floor(cu.x * 384.0), 3.0));
    vec3 mask = sub == 0 ? vec3(1.0, 0.75, 0.75) : sub == 1 ? vec3(0.75, 1.0, 0.75) : vec3(0.75, 0.75, 1.0);
    col *= mix(vec3(1.0), mask, CRT_MASK);
    col += (n - 0.5) * CRT_NOISE;
    col *= 1.0 + (cctv_hash(vec2(floor(t * CRT_FLICKER_HZ), 17.0)) - 0.5) * CRT_FLICKER;
    col *= 1.0 + CRT_ROLL * smoothstep(0.1, 0.0, abs(cu.y - fract(t * CRT_ROLL_SPEED)));
    col *= 1.0 - CRT_VIGNETTE * smoothstep(0.30, 0.75, length(c));
    float edge = min(min(cu.x, 1.0 - cu.x), min(cu.y, 1.0 - cu.y));
    col += CRT_EDGE_GLOW * vec3(0.35, 0.45, 0.55) * smoothstep(0.05, 0.0, edge);
    return vec4(clamp(col * CRT_GAIN, 0.0, 1.0), 1.0);
}

vec4 cctv_text_fragment(vec4 color, vec4 texColor, vec4 vertColor, sampler2D tex, vec2 uv, vec4 colorModulator) {
#ifdef IS_GUI
    ivec3 c = ivec3(round(vertColor.rgb * 255.0));
    if (c.r != 12 || c.g != 199) return color;
    int flags = c.b & 31;
    int palette = (c.b >> 5) & 7;
    float t = GameTime * 1200.0;
    // blink hides the glyph AND its fringes; alpha 0 is discarded by the caller's alpha test
    if ((flags & 16) != 0 && fract(t * CCTV_BLINK_HZ) > 0.5) {
        return vec4(0.0);
    }
    if (!cctv_glyph_texel(texColor.a)) {
        // an empty pixel inside a CCTV glyph cell: optional aberration fringe next to glyph pixels
        if ((flags & 4) != 0 && texColor.a < 0.003) {
            vec2 ts = 1.0 / vec2(textureSize(tex, 0));
            if (cctv_glyph_texel(texture(tex, uv - vec2(ts.x, 0.0)).a)) {
                return vec4(CCTV_FRINGE_RIGHT, CCTV_FRINGE_ALPHA * vertColor.a) * colorModulator;
            }
            if (cctv_glyph_texel(texture(tex, uv + vec2(ts.x, 0.0)).a)) {
                return vec4(CCTV_FRINGE_LEFT, CCTV_FRINGE_ALPHA * vertColor.a) * colorModulator;
            }
        }
        return color; // vanilla glyph or empty texel: exactly what vanilla computed
    }
    vec3 rgb = texColor.rgb * cctv_palette(palette);
    float a = vertColor.a;
    if ((flags & 1) != 0) {
        rgb *= 1.0 - CCTV_FLICKER * cctv_hash(vec2(floor(t * CCTV_FLICKER_HZ), 5.0));
    }
    if ((flags & 8) != 0 && mod(floor(gl_FragCoord.y), 2.0) < 1.0) {
        rgb *= CCTV_SCAN_DARK;
    }
    return vec4(rgb, a) * colorModulator;
#else
    return cctv_crt(color, tex, uv);
#endif
}

#endif

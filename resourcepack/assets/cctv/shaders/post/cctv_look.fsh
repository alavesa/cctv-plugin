#version 330

// =====================================================================================================================
// CCTV look: the full-screen camera-feed effect (cctv:standard, cctv:night_vision, cctv:thermal).
//
// A post effect cannot read plugin state. The plugin picks between pre-made variants (the post_effect/*.json files),
// which only differ in the CctvLook values below. Everything else is tuned with the constants in this file.
// Time comes from the engine's Globals block (GameTime), so grain, flicker and glitches animate on their own.
// =====================================================================================================================

// ---- lens ----------------------------------------------------------------------------------------------------------
const float BARREL = 0.10;              // barrel distortion strength. 0 = flat, 0.25 = fisheye. Pixels pushed past the
                                        // frame edge turn black (reads as the camera housing).
const float CHROMA = 0.0035;            // chromatic aberration: R/B are sampled this far (UV units) outward/inward,
                                        // growing toward the edges. 0 = off.

// ---- sync jitter / glitch bursts -----------------------------------------------------------------------------------
const float JITTER_SLOTS_PER_SEC = 6.0; // how often the "is a glitch burst happening" dice is rolled
const float JITTER_CHANCE = 0.06;       // chance per slot that a burst happens (0.06 = about one every 3 s)
const float JITTER_AMOUNT = 0.035;      // max horizontal row shift during a burst, UV units
const float JITTER_ROW_PX = 6.0;        // height of a row that shifts as one block, screen pixels
const float WOBBLE_AMOUNT = 0.0007;     // constant tiny horizontal line wobble (cheap tape/sync feel), UV units
const float WOBBLE_FREQ = 140.0;        // wobble waves per screen height
const float WOBBLE_SPEED = 3.0;         // wobble scroll speed

// ---- picture --------------------------------------------------------------------------------------------------------
const float SATURATION = 0.35;          // standard look: 0 = greyscale, 1 = original colours
const float CONTRAST = 1.12;            // standard look contrast around mid grey
const float BRIGHTNESS = 0.02;          // standard look brightness offset
const vec3  TINT = vec3(0.95, 1.0, 1.02); // standard look colour cast (slightly cold)

const float NV_GAIN = 2.0;              // night vision: light amplification
const float NV_GAMMA = 0.8;             // night vision: <1 lifts the shadows
const vec3  NV_TINT = vec3(0.30, 1.00, 0.35); // night vision phosphor colour
const float NV_FLOOR = 0.03;            // night vision: minimum glow so black isn't pure black

const float THERMAL_GAIN = 1.25;        // thermal: luminance gain before the false-colour ramp. NOTE: this is a
                                        // brightness ramp, not real heat; Minecraft has no temperature buffer.

// ---- screen texture -------------------------------------------------------------------------------------------------
const float SCANLINE_PERIOD_PX = 3.0;   // distance between scanlines, screen pixels
const float SCANLINE_STRENGTH = 0.22;   // 0 = no scanlines, 1 = black gaps
const float ROLL_STRENGTH = 0.05;       // brightness of the slow rolling bar (frame-rate beat between camera and CRT)
const float ROLL_SPEED = 0.08;          // rolling bar speed, screens per second
const float GRAIN = 0.10;               // animated film grain / sensor noise amount
const float FLICKER_HZ = 12.0;          // brightness flicker steps per second
const float FLICKER_AMOUNT = 0.05;      // brightness flicker depth
const float VIGNETTE_INNER = 0.45;      // vignette starts darkening here (0 = screen centre, 1 = screen corner)
const float VIGNETTE_OUTER = 1.05;      // fully dark here
const float VIGNETTE_STRENGTH = 0.65;   // 0 = off

// ---- viewfinder -----------------------------------------------------------------------------------------------------
// Drawn here (not in the HUD font) because the post shader knows the real screen size, so the brackets land in the
// exact corners at any resolution, aspect ratio and GUI scale.
const float BRACKET_MARGIN = 0.05;      // distance of the corner brackets from the screen edge, fraction of min(w,h)
const float BRACKET_LEN = 0.07;         // bracket arm length, fraction of min(w,h)
const float BRACKET_PX = 2.0;           // bracket line thickness, screen pixels
const vec3  BRACKET_COLOR = vec3(0.92, 0.95, 0.92);
const float BRACKET_ALPHA = 0.75;
const float RETICLE_PX = 9.0;           // centre reticle arm length, screen pixels (0 = no reticle)
const float RETICLE_GAP_PX = 3.0;       // empty gap in the reticle centre

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

// Same block and layout as minecraft:globals.glsl in the 26.3 client. It is declared here instead of #include so this
// file has no cross-file dependency. Post passes bind it (PostPass -> RenderSystem.bindDefaultUniforms).
layout(std140) uniform Globals {
    ivec3 CameraBlockPos;
    vec3 CameraOffset;
    vec2 ScreenSize;
    float GlintAlpha;
    float GameTime;
    int MenuBlurRadius;
    int UseRgss;
};

// Set per variant in post_effect/*.json
layout(std140) uniform CctvLook {
    float Mode;      // 0 standard, 1 night vision, 2 thermal
    float Intensity; // multiplier for grain, jitter and flicker (1 = as tuned above)
};

in vec2 texCoord;

out vec4 fragColor;

// Hash without sine (Dave Hoskins): stable across GPUs, unlike fract(sin(x) * big).
float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec3 thermalRamp(float x) {
    // black -> blue -> purple -> red -> orange -> yellow -> white
    vec3 c = mix(vec3(0.0, 0.0, 0.05), vec3(0.10, 0.10, 0.80), smoothstep(0.00, 0.20, x));
    c = mix(c, vec3(0.60, 0.05, 0.70), smoothstep(0.20, 0.40, x));
    c = mix(c, vec3(0.95, 0.10, 0.10), smoothstep(0.40, 0.60, x));
    c = mix(c, vec3(1.00, 0.55, 0.00), smoothstep(0.60, 0.78, x));
    c = mix(c, vec3(1.00, 0.95, 0.20), smoothstep(0.78, 0.92, x));
    return mix(c, vec3(1.0), smoothstep(0.92, 1.00, x));
}

float bracketMask(vec2 px) {
    float minDim = min(OutSize.x, OutSize.y);
    float margin = BRACKET_MARGIN * minDim;
    float len = BRACKET_LEN * minDim;
    // fold all four corners onto the bottom-left one
    vec2 q = min(px, OutSize - px) - margin;
    if (q.x < 0.0 || q.y < 0.0) return 0.0;
    bool horizontal = q.x < len && q.y < BRACKET_PX;
    bool vertical = q.y < len && q.x < BRACKET_PX;
    return (horizontal || vertical) ? 1.0 : 0.0;
}

float reticleMask(vec2 px) {
    if (RETICLE_PX <= 0.0) return 0.0;
    vec2 d = abs(px - OutSize * 0.5);
    bool h = d.y < 1.0 && d.x >= RETICLE_GAP_PX && d.x < RETICLE_GAP_PX + RETICLE_PX;
    bool v = d.x < 1.0 && d.y >= RETICLE_GAP_PX && d.y < RETICLE_GAP_PX + RETICLE_PX;
    return (h || v) ? 1.0 : 0.0;
}

void main() {
    // GameTime is (ticks % 24000 + partial) / 24000, so this is seconds and wraps every 20 minutes (one glitch frame).
    float t = GameTime * 1200.0;
    vec2 px = texCoord * OutSize;

    // 1. barrel distortion
    vec2 c = texCoord - 0.5;
    vec2 uv = 0.5 + c * (1.0 + BARREL * dot(c, c));

    // 2. sync jitter: rare bursts that shift rows sideways, plus a constant faint wobble
    float slot = floor(t * JITTER_SLOTS_PER_SEC);
    float burst = step(1.0 - JITTER_CHANCE * Intensity, hash12(vec2(slot, 7.0)));
    float row = floor(px.y / JITTER_ROW_PX);
    uv.x += (hash12(vec2(row, slot)) - 0.5) * JITTER_AMOUNT * burst;
    uv.x += sin(uv.y * WOBBLE_FREQ + t * WOBBLE_SPEED) * WOBBLE_AMOUNT;

    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    // 3. chromatic aberration, radial
    vec2 off = (uv - 0.5) * CHROMA * 2.0;
    vec3 col = vec3(texture(InSampler, uv + off).r, texture(InSampler, uv).g, texture(InSampler, uv - off).b);

    // 4. colour grading per look
    float luma = dot(col, vec3(0.299, 0.587, 0.114));
    if (Mode < 0.5) {
        col = mix(vec3(luma), col, SATURATION);
        col = (col - 0.5) * CONTRAST + 0.5 + BRIGHTNESS;
        col *= TINT;
    } else if (Mode < 1.5) {
        col = NV_TINT * pow(clamp(luma * NV_GAIN, 0.0, 1.0), NV_GAMMA) + NV_FLOOR;
    } else {
        col = thermalRamp(clamp(luma * THERMAL_GAIN, 0.0, 1.0));
    }

    // 5. scanlines and the slow rolling bar
    float scan = 0.5 + 0.5 * cos(px.y * 6.2831853 / SCANLINE_PERIOD_PX);
    col *= mix(1.0, scan, SCANLINE_STRENGTH);
    float rollPos = fract(t * ROLL_SPEED);
    col *= 1.0 + ROLL_STRENGTH * smoothstep(0.08, 0.0, abs(texCoord.y - rollPos));

    // 6. grain: new pattern every frame-ish (t advances smoothly, the offsets jump around)
    float n = hash12(px + fract(t * 61.0) * vec2(97.0, 113.0));
    col += (n - 0.5) * GRAIN * Intensity;

    // 7. brightness flicker
    col *= 1.0 + (hash12(vec2(floor(t * FLICKER_HZ), 3.0)) - 0.5) * FLICKER_AMOUNT * Intensity;

    // 8. vignette (aspect-corrected so it stays round)
    float aspect = OutSize.x / OutSize.y;
    // 0 in the centre, 1 in the screen corners
    float dist = length(c * vec2(aspect, 1.0)) / (0.5 * sqrt(aspect * aspect + 1.0));
    col *= 1.0 - VIGNETTE_STRENGTH * smoothstep(VIGNETTE_INNER, VIGNETTE_OUTER, dist);

    // 9. viewfinder
    float frame = max(bracketMask(px), reticleMask(px));
    col = mix(col, BRACKET_COLOR, frame * BRACKET_ALPHA);

    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}

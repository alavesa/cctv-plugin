#version 330

// =====================================================================================================================
// CCTV signal problems, stacked AFTER the look effect:
//   Mode 0  degraded signal (cctv:signal_weak, cctv:signal_critical), strength from SignalConfig.Strength
//   Mode 1  static burst while switching feeds (cctv:static_burst)
//   Mode 2  signal lost (cctv:signal_lost): snow over a dark picture
// The plugin decides which one applies (from camera health and session state) and swaps variants.
// =====================================================================================================================

// ---- degraded (mode 0) ----------------------------------------------------------------------------------------------
const float TEAR_BANDS = 18.0;          // horizontal bands the picture is split into for tearing
const float TEAR_RATE = 9.0;            // how often tearing re-rolls, per second
const float TEAR_CHANCE = 0.18;         // chance per band and roll to tear, times Strength
const float TEAR_SHIFT = 0.06;          // max sideways shift of a torn band, UV units
const float NOISE_MIX = 0.35;           // snow mixed over the picture at Strength 1
const float DROPOUT_CHANCE = 0.004;     // chance per pixel row and frame of a white dropout line, times Strength
const float ROLL_CHANCE = 0.12;         // chance per half-second that the picture rolls vertically (Strength >= 0.7)
const float DESAT = 0.5;                // colour lost at Strength 1

// ---- static burst (mode 1) and signal lost (mode 2) -----------------------------------------------------------------
const float SNOW_BLOCK_PX = 2.0;        // snow grain size, screen pixels
const float BURST_MIX = 0.85;           // static burst: how much snow covers the picture
const float LOST_DARKEN = 0.25;         // signal lost: picture brightness left under the snow
const vec3  LOST_TINT = vec3(0.75, 0.80, 0.95); // signal lost: snow colour

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

// Same block and layout as minecraft:globals.glsl (26.2); see cctv_look.fsh.
layout(std140) uniform Globals {
    ivec3 CameraBlockPos;
    vec3 CameraOffset;
    vec2 ScreenSize;
    float GlintAlpha;
    float GameTime;
    int MenuBlurRadius;
    int UseRgss;
};

layout(std140) uniform SignalConfig {
    float Strength; // 0..1
    float Mode;     // 0 degraded, 1 static burst, 2 signal lost
};

in vec2 texCoord;

out vec4 fragColor;

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float snow(vec2 px, float t) {
    return hash12(floor(px / SNOW_BLOCK_PX) + fract(t * 37.0) * vec2(131.0, 71.0));
}

void main() {
    float t = GameTime * 1200.0;
    vec2 px = texCoord * OutSize;
    vec2 uv = texCoord;

    if (Mode < 0.5) {
        float roll = floor(t * TEAR_RATE);
        float band = floor(uv.y * TEAR_BANDS);
        float tear = step(1.0 - TEAR_CHANCE * Strength, hash12(vec2(band, roll)));
        uv.x += (hash12(vec2(band, roll + 1.0)) - 0.5) * 2.0 * TEAR_SHIFT * tear;
        float rolling = step(0.7, Strength) * step(1.0 - ROLL_CHANCE, hash12(vec2(floor(t * 2.0), 9.0)));
        uv.y = fract(uv.y + rolling * fract(t * 2.0) * 0.5);
        vec3 col = texture(InSampler, fract(uv)).rgb;
        float luma = dot(col, vec3(0.299, 0.587, 0.114));
        col = mix(col, vec3(luma), DESAT * Strength);
        col = mix(col, vec3(snow(px, t)), NOISE_MIX * Strength);
        float drop = step(1.0 - DROPOUT_CHANCE * Strength, hash12(vec2(floor(px.y), floor(t * 30.0))));
        col = mix(col, vec3(1.0), drop * 0.8);
        fragColor = vec4(col, 1.0);
    } else if (Mode < 1.5) {
        vec3 col = texture(InSampler, uv).rgb;
        float bar = smoothstep(0.12, 0.0, abs(uv.y - fract(t * 1.7)));
        col = mix(col, vec3(snow(px, t)), clamp(BURST_MIX * Strength + bar * 0.3, 0.0, 1.0));
        fragColor = vec4(col, 1.0);
    } else {
        vec3 col = texture(InSampler, uv).rgb * LOST_DARKEN;
        col = max(col, LOST_TINT * snow(px, t) * Strength);
        fragColor = vec4(col, 1.0);
    }
}

#version 330

// =====================================================================================================================
// CCTV digital zoom (cctv:zoom_2x, cctv:zoom_3x, cctv:zoom_4x). Crops the centre and scales it up. Runs BEFORE the
// look effect, so scanlines and grain stay sharp. The input is sampled without bilinear filtering (the post-chain
// default), so zooming in shows chunky pixels, like a real digital zoom.
// =====================================================================================================================

uniform sampler2D InSampler;

layout(std140) uniform ZoomConfig {
    float Zoom; // magnification, set per variant in post_effect/zoom_*.json
};

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 uv = 0.5 + (texCoord - 0.5) / max(Zoom, 1.0);
    fragColor = vec4(texture(InSampler, uv).rgb, 1.0);
}

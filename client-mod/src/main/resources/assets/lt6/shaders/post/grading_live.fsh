#version 330

// Fullscreen GPU colour grading; HUD remains untouched because it is rendered
// after post processing. Depth == 1 means sky, kept unchanged for Custom Sky.
uniform sampler2D InSampler;
uniform sampler2D DepthSampler;
layout(std140) uniform GradingConfig {
    vec4 ColorTuning; // saturation, contrast, warmth, brightness
    vec4 ExtraTuning; // strength, vibrance, unused, unused
};
in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 source = texture(InSampler, texCoord);
    if (texture(DepthSampler, texCoord).r >= 0.99999) {
        fragColor = source;
        return;
    }

    vec3 rgb = source.rgb;
    float luminance = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    rgb = mix(vec3(luminance), rgb, clamp(ColorTuning.x, 0.0, 2.0));

    // Vibrance boosts muted colours more than already vivid colours.
    float chroma = max(rgb.r, max(rgb.g, rgb.b)) - min(rgb.r, min(rgb.g, rgb.b));
    float vibrance = 1.0 + (ExtraTuning.y - 1.0) * (1.0 - clamp(chroma, 0.0, 1.0));
    float average = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    rgb = mix(vec3(average), rgb, clamp(vibrance, 0.0, 2.0));

    rgb = (rgb - vec3(0.5)) * clamp(ColorTuning.y, 0.5, 2.0) + vec3(0.5);
    float warmth = clamp(ColorTuning.z, -1.0, 1.0);
    rgb *= vec3(1.0 + 0.26 * warmth, 1.0 + 0.025 * warmth, 1.0 - 0.26 * warmth);
    rgb *= clamp(ColorTuning.w, 0.5, 2.0);
    rgb = clamp(rgb, 0.0, 1.0);
    fragColor = vec4(mix(source.rgb, rgb, clamp(ExtraTuning.x, 0.0, 1.0)), source.a);
}

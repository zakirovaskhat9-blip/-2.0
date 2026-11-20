#version 330

// One fullscreen GPU pass for world-only color grading. Skybox pixels are
// preserved by checking the main depth texture, rather than drawing tints.
uniform sampler2D InSampler;
uniform sampler2D DepthSampler;
layout(std140) uniform GradingConfig {
    vec4 ColorTuning; // saturation, contrast, warmth, reserved
};
in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 src = texture(InSampler, texCoord);
    float depth = texture(DepthSampler, texCoord).r;
    if (depth > 0.99999) { // sky: retain exact pixels
        fragColor = src;
        return;
    }
    vec3 rgb = src.rgb;
    float luminance = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    rgb = mix(vec3(luminance), rgb, ColorTuning.x);
    rgb = (rgb - vec3(0.5)) * ColorTuning.y + vec3(0.5);
    float warmth = ColorTuning.z;
    rgb *= vec3(1.0 + warmth * 0.28, 1.0 + warmth * 0.035, 1.0 - warmth * 0.28);
    fragColor = vec4(clamp(rgb, 0.0, 1.0), src.a);
}

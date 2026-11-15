#version 330
#extension GL_ARB_separate_shader_objects : require

// An asteroid's flash: the picture overexposed, the brightest of it blown out first, until at full strength
// nothing is left but white.

uniform sampler2D InSampler;

layout(std140) uniform FlashConfig {
    float Strength;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// A little warm, as the light of something burning is.
const vec3 GLARE = vec3(1.0, 0.98, 0.93);

void main() {
    vec3 seen = texture(InSampler, texCoord).rgb;
    // A screen blend toward the glare: what is left of the picture's darkness shrinks with the square of the strength.
    float kept = (1.0 - Strength) * (1.0 - Strength);
    fragColor = vec4(GLARE - (GLARE - seen) * kept, 1.0);
}

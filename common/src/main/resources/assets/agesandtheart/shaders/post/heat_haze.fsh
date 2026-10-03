#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:globals.glsl>

// The air shimmering over a plasma sea (design 7.1.2): the scene sampled through ripples that rise, as heat
// does, strongest at the bottom of the screen where the sea is, and a pale wash over all at full strength.

uniform sampler2D InSampler;

layout(std140) uniform HazeConfig {
    float Strength;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// GameTime is the fraction of a twenty-minute day.
const float SECONDS_A_DAY = 1200.0;

// How far the picture is pushed at full strength, as a share of the screen.
const float MOST_PUSH = 0.007;

void main() {
    float seconds = GameTime * SECONDS_A_DAY;
    // Stronger low on the screen, where the heat comes from; a third of it at the top.
    float fromBelow = mix(1.0, 0.35, texCoord.y);
    // Two crossed ripples climbing at different speeds, so the shimmer never settles into a pattern.
    float climb = sin(texCoord.y * 70.0 - seconds * 4.1 + sin(texCoord.x * 23.0 + seconds * 0.7) * 1.5);
    float sway = sin(texCoord.y * 41.0 - seconds * 2.3 + texCoord.x * 17.0);
    vec2 push = vec2(climb * 0.6 + sway * 0.4, climb * 0.3) * MOST_PUSH * Strength * fromBelow;
    vec3 seen = texture(InSampler, clamp(texCoord + push, vec2(0.0), vec2(1.0))).rgb;
    // The glare of it: pale and a little green, only as the heat grows hard to stand in.
    vec3 washed = mix(seen, seen * vec3(1.04, 1.10, 1.02) + vec3(0.05, 0.08, 0.04), Strength * Strength * 0.35);
    fragColor = vec4(washed, 1.0);
}

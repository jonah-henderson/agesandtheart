#version 330
// SPIR-V since 26.3: every stage-crossing declaration needs a location.
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:fog.glsl>
// Sorted transparency compiles this source once more per OIT stage -- see `SandColumnRenderer`'s pipeline set.
#include <minecraft:oit.glsl>

layout(location = 0) in vec2 aroundAndDown;
layout(location = 1) in vec4 layer;
layout(location = 2) in vec4 worldLight;
layout(location = 3) in float sphericalVertexDistance;
layout(location = 4) in float cylindricalVertexDistance;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

const float TAU = 6.28318530718;

// How many times a day the bands go round, in whole turns: `GameTime` is a day fraction and wraps at dawn, so
// a whole number of turns wraps with it and nothing jumps. Every rate below multiplies these by a whole number.
const float FUNNEL_TURNS_A_DAY = 1600.0;
const float WALL_TURNS_A_DAY = 120.0;

// Which part `layer.g` names: the outer funnel at 0, its core at a quarter, the dust at a half, the wall cloud at
// three quarters. Read with a margin either side.
const float CORE_FROM = 0.125;
const float DUST_FROM = 0.375;
const float WALL_FROM = 0.625;

const vec3 FUNNEL_DARK = vec3(0.30, 0.31, 0.33);
const vec3 FUNNEL_PALE = vec3(0.70, 0.71, 0.73);
const vec3 CORE_GREY = vec3(0.36, 0.37, 0.39);
const vec3 DUST_DARK = vec3(0.28, 0.23, 0.17);
const vec3 DUST_PALE = vec3(0.58, 0.50, 0.40);
const vec3 WALL_DARK = vec3(0.22, 0.23, 0.26);
const vec3 WALL_PALE = vec3(0.42, 0.43, 0.46);

// How much darker the foot of the funnel is than its top, where it is thick with what it has taken up.
const float FOOT_DARKENS_BY = 0.3;

// Below this a fragment is thrown away rather than blended, so a gap in the bands writes no depth and lets what is
// behind it through -- see `sand_column.fsh`, whose reason this is.
const float TOO_FAINT_TO_STAND_IN_FRONT = 0.22;

const float AMPLITUDE_SUM = 1.0 + 0.6 + 0.35;

/**
 * Streaks of cloud going round, in 0..1: slanted, so they read as a spiral, and at three scales moving at three whole
 * rates, so the near ones slide over the far ones.
 */
float bandsAt(vec2 there, float drifted, float phase) {
    float around = there.x * TAU;
    float down = there.y;
    float value = sin(around * 6.0 + down * 0.10 - drifted + phase);
    value += 0.6 * sin(around * 11.0 + down * 0.23 - drifted * 2.0 + phase * 2.0);
    value += 0.35 * sin(around * 19.0 - down * 0.07 - drifted * 3.0 + phase * 3.0);
    return clamp(value / AMPLITUDE_SUM * 0.5 + 0.5, 0.0, 1.0);
}

void main() {
    float phase = layer.r * TAU;
    float part = layer.g;
    float downShare = layer.b;

    vec3 tone;
    float alpha;
    if (part >= WALL_FROM) {
        float bands = bandsAt(aroundAndDown * vec2(1.0, 0.3), GameTime * TAU * WALL_TURNS_A_DAY, phase);
        tone = mix(WALL_DARK, WALL_PALE, bands);
        alpha = layer.a * (0.7 + 0.3 * bands);
    } else if (part >= DUST_FROM) {
        float bands = bandsAt(aroundAndDown, GameTime * TAU * FUNNEL_TURNS_A_DAY * 2.0, phase);
        tone = mix(DUST_DARK, DUST_PALE, bands);
        // Thinning away upward, where `downShare` is how far up the skirt.
        alpha = layer.a * bands * (1.0 - downShare * downShare);
    } else if (part >= CORE_FROM) {
        float bands = bandsAt(aroundAndDown, GameTime * TAU * FUNNEL_TURNS_A_DAY, phase + 1.7);
        tone = CORE_GREY * (0.85 + 0.3 * bands) * (1.0 - FOOT_DARKENS_BY * downShare);
        alpha = layer.a * (0.8 + 0.2 * bands);
    } else {
        float bands = bandsAt(aroundAndDown, GameTime * TAU * FUNNEL_TURNS_A_DAY, phase);
        tone = mix(FUNNEL_DARK, FUNNEL_PALE, bands) * (1.0 - FOOT_DARKENS_BY * downShare);
        alpha = layer.a * (0.3 + 0.7 * bands * bands);
    }
    tone *= worldLight.rgb;
    if (alpha < TOO_FAINT_TO_STAND_IN_FRONT) discard;

#ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, alpha);
#else
    vec4 colour = vec4(tone, alpha);
    vec4 fogged = FogColor;
#ifdef OIT_ACCUMULATE
    colour = sampleColorForAccumulation(colour);
    fogged = vec4(FogColor.rgb * colour.a, FogColor.a);
#endif
    fragColor = apply_fog(
        colour,
        sphericalVertexDistance,
        cylindricalVertexDistance,
        FogEnvironmentalStart,
        FogEnvironmentalEnd,
        FogRenderDistanceStart,
        FogRenderDistanceEnd,
        fogged
    ) * ColorModulator;
#endif
}

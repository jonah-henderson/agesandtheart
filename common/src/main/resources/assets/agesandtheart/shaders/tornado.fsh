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

// How many times a day the bands go round at the slowest, in whole turns: `GameTime` is a day fraction and wraps at
// dawn, so a whole number of turns wraps with it and nothing jumps. Every rate below multiplies these by a whole number.
const float FUNNEL_TURNS_A_DAY = 3000.0;
const float SLEEVE_TURNS_A_DAY = 3600.0;
const float DUST_TURNS_A_DAY = 1200.0;
// Each slab or streak goes at one to this many times the slowest, drawn from its seed.
const float FUNNEL_FASTEST = 3.0;
const float SLEEVE_FASTEST = 3.0;
const float DUST_FASTEST = 3.0;
// And now and then surges to this much faster again, easing into it and out of it, once every thirty to fifty-five
// seconds (this many times a day), for about a third of that. Its average pace is 3/8 of the boost over the slowest,
// so every turns-a-day above times 3/8 of this must stay a whole number, or the bands jump at dawn.
const float SURGE_BOOST = 0.8;
const float FEWEST_SURGES_A_DAY = 22.0;
const float MORE_SURGES_A_DAY = 19.0;

// Which part `layer.g` names, in fifths: the funnel, its sleeve, the dust at its foot, the spray at its foot over
// water, and the overcast. Read with a margin either side.
const float SLEEVE_FROM = 0.1;
const float DUST_FROM = 0.3;
const float SPRAY_FROM = 0.5;
const float OVERCAST_FROM = 0.7;

const vec3 FUNNEL_DARK = vec3(0.30, 0.31, 0.33);
const vec3 FUNNEL_PALE = vec3(0.62, 0.63, 0.65);
const vec3 DUST_DARK = vec3(0.28, 0.23, 0.17);
const vec3 DUST_PALE = vec3(0.58, 0.50, 0.40);
const vec3 SPRAY_DARK = vec3(0.55, 0.66, 0.78);
const vec3 SPRAY_PALE = vec3(0.92, 0.96, 1.00);
const vec3 OVERCAST_GREY = vec3(0.46, 0.47, 0.50);
// How far each cell of the overcast is lighter or darker than the next.
const float OVERCAST_MOTTLE = 0.12;

// How much darker the foot of the funnel is than its top, where it is thick with what it has taken up.
const float FOOT_DARKENS_BY = 0.3;

// The funnel takes on the overcast's grey over the top of it, so the two meet without an edge.
const float MERGES_DOWN_TO = 0.15;
const float OVERCAST_UNDERSIDE = 0.7;

// Everything but the sleeve is opaque and its gaps thrown away. The sleeve blends, but writes depth as it does, which
// hides the water drawn after it -- see `sand_column.fsh` -- so it is solid enough where it stands, a real hole where
// it does not, and thins away at the top and the foot, where the overcast and water stand behind it.
const float DUST_GAP = 0.3;
const float SLEEVE_GAP = 0.3;
const float SLEEVE_SOLIDITY = 0.75;
const float SLEEVE_FROM_THE_TOP = 0.15;
const float SLEEVE_TO_THE_FOOT = 0.85;

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

/**
 * How far one slab's or streak's bands have gone round, from its [seed]: each at its own whole-numbered pace, and now
 * and then surging faster and easing back, so neighbours slide past each other and pull ahead in turn.
 *
 * The pace is `turns * (1 + SURGE_BOOST * pulse)`, the pulse `((1 - cos x) / 2)^2` -- nought most of the time, rising
 * smoothly to one and back -- which is `3/8 - cos x / 2 + cos 2x / 8`; what is returned is that integrated over the day.
 */
float driftOf(float seed, float turnsADay, float fastest) {
    float turns = turnsADay * (1.0 + floor(fract(seed * 13.0) * fastest));
    float surgesADay = FEWEST_SURGES_A_DAY + floor(fract(seed * 29.0) * MORE_SURGES_A_DAY);
    float surgeAngle = TAU * surgesADay;
    float x = GameTime * surgeAngle + seed * TAU;
    float pulsed = 0.375 * GameTime - 0.5 * sin(x) / surgeAngle + 0.0625 * sin(2.0 * x) / surgeAngle;
    return TAU * turns * (GameTime + SURGE_BOOST * pulsed);
}

void main() {
    // For the funnel, its sleeve and the dust, r is the seed of this slab or level; for the overcast, its mottle.
    float seed = layer.r;
    float phase = seed * TAU;
    float part = layer.g;
    float downShare = layer.b;

    vec3 tone;
    float alpha = 1.0;
    if (part >= OVERCAST_FROM) {
        // b: how this face is shaded.
        tone = OVERCAST_GREY * downShare * (1.0 - OVERCAST_MOTTLE * 0.5 + OVERCAST_MOTTLE * seed);
    } else if (part >= DUST_FROM) {
        float bands = bandsAt(aroundAndDown, driftOf(seed, DUST_TURNS_A_DAY, DUST_FASTEST), phase);
        // Thinning away upward, where `downShare` is how far up the skirt.
        if (bands * (1.0 - downShare * downShare) * layer.a < DUST_GAP) discard;
        tone = part >= SPRAY_FROM ? mix(SPRAY_DARK, SPRAY_PALE, bands) : mix(DUST_DARK, DUST_PALE, bands);
    } else if (part >= SLEEVE_FROM) {
        float bands = bandsAt(aroundAndDown, driftOf(seed, SLEEVE_TURNS_A_DAY, SLEEVE_FASTEST), phase);
        float ends = smoothstep(0.0, SLEEVE_FROM_THE_TOP, downShare) * (1.0 - smoothstep(SLEEVE_TO_THE_FOOT, 1.0, downShare));
        alpha = smoothstep(0.45, 0.8, bands) * SLEEVE_SOLIDITY * ends * layer.a;
        if (alpha < SLEEVE_GAP) discard;
        tone = mix(FUNNEL_DARK, FUNNEL_PALE, bands) * (1.0 - FOOT_DARKENS_BY * downShare);
    } else {
        float bands = bandsAt(aroundAndDown, driftOf(seed, FUNNEL_TURNS_A_DAY, FUNNEL_FASTEST), phase);
        // Breaking up, it frays from its darker bands.
        if (1.0 - bands > layer.a) discard;
        tone = mix(FUNNEL_DARK, FUNNEL_PALE, bands) * (1.0 - FOOT_DARKENS_BY * downShare);
        tone = mix(OVERCAST_GREY * OVERCAST_UNDERSIDE, tone, smoothstep(0.0, MERGES_DOWN_TO, downShare));
    }
    tone *= worldLight.rgb;

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

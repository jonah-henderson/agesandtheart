#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:fog.glsl>

in vec2 aroundAndDown;
in vec4 layer;
in vec4 worldLight;
in float sphericalVertexDistance;
in float cylindricalVertexDistance;

out vec4 fragColor;

const float TAU = 6.28318530718;

// **The fastest a column may pour, in whole turns a day**, and whole is the load-bearing word.
// `GameTime` is a day *fraction* -- `((gameTime % 24000) + partialTick) / 24000` -- so it wraps at dawn. A
// whole number of turns wraps with it and nothing moves; a fractional one would jerk the sand a hand's
// width every morning. Every coefficient below multiplies the drift by a whole number for the same reason,
// including the per-lane rates, which is why those are drawn from a small set of integers.
//
// **A column's own rate arrives quantised rather than continuous, and that is the same rule.** It rides in
// a colour channel, so it is already a step of 1/255 of this, and rounding it back to a whole number of
// turns is what keeps a fierce column's faster pour wrapping as cleanly as an ordinary one's.
//
// **`ColumnBehaviour.FASTEST_POUR` is this same number in Kotlin**, and `SandfallCheck` reads this file to
// say so. If they ever disagree every column pours wrong and the drift stops landing on a whole turn.
const float FASTEST_FALL = 2550.0;

// How many lanes of falling sand there are to a block. Fine enough to read as grains at arm's length,
// coarse enough that a column seen from across the Age is not a shimmer.
const float LANES_A_BLOCK = 1.7;

// How far below the top the column has fully arrived, in blocks. It has no lid: there is no second planet
// overhead to pour from, so instead the sand thins away into the sky and reads as coming from further up
// than the sky goes.
const float ARRIVES_BY = 64.0;

// A sand in shadow and a sand in the light. Wide apart on purpose -- a narrow pair reads as a beige beam,
// which is what the first attempt at this was.
const vec3 SHADED = vec3(0.42, 0.33, 0.21);
const vec3 LIT = vec3(1.00, 0.94, 0.76);

// What `layer.g` reads at or above for the shell that is solid. The three shells are 0, a half and 1.
const float CORE_IS_AT = 0.75;

// The four amplitudes in `fallAt`, summed — what the total is divided by to land back in -1..1. Derived,
// so it moves with them; the frequencies themselves are free.
const float AMPLITUDE_SUM = 1.0 + 0.7 + 0.5 + 0.3;

/** A number that is settled for a lane and unrelated to its neighbours'. */
float hashOf(float lane) {
    return fract(sin(lane * 12.9898) * 43758.5453);
}

/**
 * How much sand is falling at this point of this lane, in 0..1.
 *
 * **Lanes, not a field.** Sand falls in narrow vertical streaks, so `x` is stepped into lanes rather than
 * read smoothly: a smooth field gives diagonal banding that reads as a drifting curtain, which is what the
 * first cut looked like and the wrong thing entirely. Each lane gets a phase and a rate of its own, so
 * neighbours fall out of step and at different speeds — which is the whole of the illusion, because the
 * eye reads differential motion as depth.
 *
 * `sin(k·down - w·t)` holds its phase where `down` grows with `t`, so a feature travels toward larger
 * `down` — downward, because `down` measures from the top. Adding the drift instead would run the sand
 * upward and read as smoke.
 */
float fallAt(vec2 there, float drifted) {
    float lane = floor(there.x * LANES_A_BLOCK);
    float phase = hashOf(lane) * TAU;
    // One of three whole rates, so the drift still completes whole turns in a day and nothing jumps at dawn.
    float rate = 1.0 + floor(hashOf(lane + 7.0) * 3.0);
    float down = there.y;

    float value = sin(down * 0.75 + phase - drifted * rate);
    value += 0.7 * sin(down * 0.24 + phase * 2.0 - drifted * rate);
    value += 0.5 * sin(down * 1.90 + phase * 3.0 - drifted * (rate + 1.0));
    // The fine one is the grain: close up it is what stops a lane reading as a painted stripe.
    value += 0.3 * sin(down * 4.60 + phase * 5.0 - drifted * (rate + 2.0));
    return clamp(value / AMPLITUDE_SUM * 0.5 + 0.5, 0.0, 1.0);
}

void main() {
    // Rounded to a whole number of turns a day — see FASTEST_FALL. A column that buries deeper pours
    // visibly faster, which is the one thing tying what you can see to what it is doing to the ground.
    float fallsADay = floor(layer.b * FASTEST_FALL + 0.5);
    float drifted = GameTime * TAU * fallsADay;

    // **Which of the three shells this is**: 0 the outer, a half the inner, 1 the core. They read different
    // lanes and fall at different rates, so the near ones slide across the far ones and the stack reads as
    // depth — two cloud decks over one patch of ground want exactly this (Ephemeris `CloudDeck.noiseOffsetX`),
    // or they mirror each other and read as one flat sheet.
    float shell = layer.g;
    vec2 there = aroundAndDown + vec2(layer.r * 64.0 + shell * 26.0, shell * 82.0);
    float fall = fallAt(there, drifted * (1.0 + shell));

    // Lit by the world it stands in, so it goes down with the sun and is dark in a cave.
    vec3 tone = mix(SHADED, LIT, fall) * worldLight.rgb;
    // **The holes are what make it sand rather than a sheet.** Alpha follows the fall hard, so a lane that
    // is between grains is very nearly clear and the curtain is something you see the world through.
    //
    // **Except the core, which is solid.** Three translucent shells read as depth and also as a thing you
    // can see straight through, and a column of sand is not that. The innermost keeps every bit of the
    // roil and none of the transparency, so the depth survives and the seeing-through does not — and from
    // inside it, with neither face culled, there is nothing to see out of at all.
    float solid = step(CORE_IS_AT, shell);
    float alpha = mix(layer.a * fall * fall, 1.0, solid);
    alpha *= smoothstep(0.0, ARRIVES_BY, aroundAndDown.y);
    if (alpha <= 0.01) discard;

    fragColor = apply_fog(
        vec4(tone, alpha),
        sphericalVertexDistance,
        cylindricalVertexDistance,
        FogEnvironmentalStart,
        FogEnvironmentalEnd,
        FogRenderDistanceStart,
        FogRenderDistanceEnd,
        FogColor
    ) * ColorModulator;
}

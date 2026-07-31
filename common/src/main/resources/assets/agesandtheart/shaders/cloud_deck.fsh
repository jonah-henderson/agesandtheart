#version 330

#moj_import <minecraft:dynamictransforms.glsl>

layout(std140) uniform DeckInfo {
    vec4 LowTone;
    vec4 HighTone;
    vec4 SampleAndRoil;
    vec4 Extent;
};

in float faceBrightness;
in vec2 worldSample;

out vec4 fragColor;

// The four sine amplitudes below, summed — what the total is divided by to land back in -1..1. Derived,
// so it must be updated with them; the frequencies themselves are free.
const float SINE_AMPLITUDE_SUM = 1.0 + 0.7 + 0.5 + 0.4;

// Cheap value noise: a sum of drifting sines, in 0..1. Evaluated per fragment, which is the change from
// the renderer this replaces — that one could only afford it once per 32-block vertex, so the roil was a
// coarse interpolated wash. The maths is unchanged; only where it runs is.
float roilAt(vec2 world, float drifted) {
    float value = sin(world.x * 0.018 + drifted);
    value += 0.7 * sin(world.y * 0.021 - drifted * 0.9);
    value += 0.5 * sin((world.x + world.y) * 0.012 + drifted * 1.4);
    value += 0.4 * cos((world.x - world.y) * 0.015 - drifted * 0.7);
    return clamp((value / SINE_AMPLITUDE_SUM) * 0.5 + 0.5, 0.0, 1.0);
}

void main() {
    float drifted = SampleAndRoil.z;
    float contrast = SampleAndRoil.w;

    float density = roilAt(worldSample, drifted);
    float toned = clamp((density - 0.5) * contrast + 0.5, 0.0, 1.0);

    vec3 tone = mix(LowTone.rgb, HighTone.rgb, toned) * faceBrightness;
    // Near-opaque, and a touch more so where the deck is dense.
    float alpha = 0.92 + 0.08 * density;

    fragColor = vec4(tone, alpha) * ColorModulator;
}

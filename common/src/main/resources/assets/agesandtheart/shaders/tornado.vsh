#version 330
// SPIR-V since 26.3: every stage-crossing declaration needs a location.
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:fog.glsl>
#include <minecraft:sample_lightmap.glsl>

// One face of a tornado: its funnel, the dust at its foot or its wall cloud. Position arrives camera-relative,
// so what a vertex is part of rides in UV0 and Color -- see `TornadoRenderer`.
layout(location = 0) in vec3 Position;
// x: how far around the square this corner stands, in turns. y: how far below the top it is, in blocks.
layout(location = 1) in vec2 UV0;
// r: this tornado's own phase. g: which part. b: how far down the funnel. a: how solid, before the bands thin it.
layout(location = 3) in vec4 Color;
// The world's light where the tornado stands, so it darkens with the day.
layout(location = 2) in ivec2 UV2;

uniform sampler2D Sampler2;

layout(location = 0) out vec2 aroundAndDown;
layout(location = 1) out vec4 layer;
layout(location = 2) out vec4 worldLight;
layout(location = 3) out float sphericalVertexDistance;
layout(location = 4) out float cylindricalVertexDistance;

void main() {
    vec4 inView = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * inView;

    sphericalVertexDistance = fog_spherical_distance(inView.xyz);
    cylindricalVertexDistance = fog_cylindrical_distance(inView.xyz);

    aroundAndDown = UV0;
    layer = Color;
    worldLight = sample_lightmap(Sampler2, UV2);
}

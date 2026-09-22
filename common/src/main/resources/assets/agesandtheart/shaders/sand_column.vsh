#version 330
// SPIR-V since 26.3: every stage-crossing declaration needs a location.
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:fog.glsl>
#include <minecraft:sample_lightmap.glsl>

// One face of a column's prism. Position arrives already in camera-relative world space -- the pose is
// applied on the CPU when the geometry is written -- so nothing here can work out where on the column a
// vertex is. That is what UV0 and Color are carrying.
layout(location = 0) in vec3 Position;
// x: how far around the prism this corner stands, in blocks, running continuously across all four faces so
// the roil does not mirror at the corners. y: how far *below the top* it is, in blocks.
layout(location = 1) in vec2 UV0;
// r: this column's own phase, so two standing at once do not fall in step.
// g: which of the two nested prisms this is.  a: how solid the layer is before the roil thins it.
layout(location = 3) in vec4 Color;
// Where the column stands, in the world's own light. Without this a column is full-bright: it kept its
// noon colour after dark and lit itself in a cave, which is what a curtain of sand emphatically does not do.
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

    // Fogged like everything else in the world: a column is meant to be seen from a long way off, and one
    // that ignored the fog would read as a sticker on the screen rather than a thing standing in the Age.
    sphericalVertexDistance = fog_spherical_distance(inView.xyz);
    cylindricalVertexDistance = fog_cylindrical_distance(inView.xyz);

    aroundAndDown = UV0;
    layer = Color;
    // One value for the whole column, taken where the column stands — `EntityRenderState.lightCoords`,
    // which is what every other entity is lit by.
    worldLight = sample_lightmap(Sampler2, UV2);
}

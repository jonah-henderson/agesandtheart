#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:fog.glsl>

// One face of a column's prism. Position arrives already in camera-relative world space -- the pose is
// applied on the CPU when the geometry is written -- so nothing here can work out where on the column a
// vertex is. That is what UV0 and Color are carrying.
in vec3 Position;
// x: how far around the prism this corner stands, in blocks, running continuously across all four faces so
// the roil does not mirror at the corners. y: how far *below the top* it is, in blocks.
in vec2 UV0;
// r: this column's own phase, so two standing at once do not fall in step.
// g: which of the two nested prisms this is.  a: how solid the layer is before the roil thins it.
in vec4 Color;

out vec2 aroundAndDown;
out vec4 layer;
out float sphericalVertexDistance;
out float cylindricalVertexDistance;

void main() {
    vec4 inView = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * inView;

    // Fogged like everything else in the world: a column is meant to be seen from a long way off, and one
    // that ignored the fog would read as a sticker on the screen rather than a thing standing in the Age.
    sphericalVertexDistance = fog_spherical_distance(inView.xyz);
    cylindricalVertexDistance = fog_cylindrical_distance(inView.xyz);

    aroundAndDown = UV0;
    layer = Color;
}

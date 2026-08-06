#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// One deck's slab: a unit box, scaled and lifted into place by ModelViewMat. Position carries the local
// corner and Color carries only a face brightness — the roil itself is left to the fragment stage, which
// is the whole reason this pipeline exists.
in vec3 Position;
in vec4 Color;

layout(std140) uniform DeckInfo {
    vec4 LowTone;
    vec4 HighTone;
    // xy: where this deck reads the noise field, in world units, camera included.
    // z: the already-drifted time it reads at.  w: how hard the roil is pushed toward its extremes.
    vec4 SampleAndRoil;
    // x: half the slab's width in blocks, which turns a unit corner back into a world distance.
    vec4 Extent;
};

out float faceBrightness;
out vec2 worldSample;
// How far out this corner is, as a share of the slab's half-width: 0 overhead, 1 at the edge midpoints
// and about 1.41 at the corners. What turns a square slab into a disc — see the fragment stage.
out float reach;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    faceBrightness = Color.r;

    // Where in the world this corner reads the roil. Position is a unit corner, so scaling it back up by
    // the slab's half-width and adding the deck's offset — which already carries the camera — lands in
    // world space. That is what keeps the pattern still as the player walks through it, rather than
    // dragging along with them.
    worldSample = Position.xz * Extent.x + SampleAndRoil.xy;
    reach = length(Position.xz) * 2.0;
}

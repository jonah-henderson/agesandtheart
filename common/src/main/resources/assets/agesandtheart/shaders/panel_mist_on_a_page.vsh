#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;

out vec4 vertexColor;
// Where on the panel this fragment is, in the page-pixels the book screen measures its mist in, so a panel
// on a lectern wears the same banks as one in a book. Off the texture coordinates rather than the position,
// which in the world is relative to the camera and would slide the mist about as the viewer moved.
out vec2 acrossThePage;

// The book screen's panel, in page-pixels.
const vec2 PANEL = vec2(104.0, 65.0);

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    acrossThePage = UV0 * PANEL;
}

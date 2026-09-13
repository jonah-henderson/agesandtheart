#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec4 Color;

out vec4 vertexColor;
// Where on the panel this fragment is, in page-pixels: a panel's picture is laid down measured in them, so the
// banks are the same size whether the picture ends up in a book or on a lectern.
out vec2 acrossThePage;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    acrossThePage = Position.xy;
}

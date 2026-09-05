#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec4 Color;

out vec4 vertexColor;
// Where on the page this fragment is, which is what the mist is a field over. Screen coordinates rather
// than panel-local ones because `fill` sends corners and nothing else, and mist has no edges to line up.
out vec2 acrossThePage;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    acrossThePage = Position.xy;
}

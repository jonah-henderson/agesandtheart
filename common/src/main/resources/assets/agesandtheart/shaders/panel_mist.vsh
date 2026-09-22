#version 330
// SPIR-V since 26.3: every stage-crossing declaration needs a location.
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;

layout(location = 0) out vec4 vertexColor;
// Where on the panel this fragment is, in page-pixels: a panel's picture is laid down measured in them, so the
// banks are the same size whether the picture ends up in a book or on a lectern.
layout(location = 1) out vec2 acrossThePage;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    acrossThePage = Position.xy;
}

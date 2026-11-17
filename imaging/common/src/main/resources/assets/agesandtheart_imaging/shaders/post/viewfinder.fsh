#version 330
#extension GL_ARB_separate_shader_objects : require

// Looking through an image recorder: a slight fisheye and vignette, and the 16:9 crop the picture will be taken from
// marked by shading what falls outside it. Only for the eye; a picture is taken without it.

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// How far the middle swells toward the corners, which stay where they are.
const float SWELL = 0.14;
// How dark the corners go, and from how far out the darkening starts.
const float VIGNETTE = 0.5;
const float VIGNETTE_FROM = 0.35;
// What is left of the light outside the crop.
const float OUTSIDE_THE_CROP = 0.45;
const float PICTURE_ASPECT = 16.0 / 9.0;

void main() {
    vec2 size = vec2(textureSize(InSampler, 0));
    float aspect = size.x / size.y;

    vec2 centred = texCoord * 2.0 - 1.0;
    // Distance from the middle, measured on the screen as it is shaped, so 1 at the corners.
    vec2 shaped = centred * vec2(aspect, 1.0);
    float reach = dot(shaped, shaped) / (aspect * aspect + 1.0);

    vec2 sampled = centred * (1.0 + SWELL * reach) / (1.0 + SWELL);
    vec3 seen = texture(InSampler, sampled * 0.5 + 0.5).rgb;

    seen *= 1.0 - VIGNETTE * smoothstep(VIGNETTE_FROM, 1.0, reach);

    vec2 cropHalf = aspect > PICTURE_ASPECT ? vec2(PICTURE_ASPECT / aspect, 1.0) : vec2(1.0, aspect / PICTURE_ASPECT);
    bool isOutsideTheCrop = any(greaterThan(abs(centred), cropHalf));
    if (isOutsideTheCrop) seen *= OUTSIDE_THE_CROP;

    fragColor = vec4(seen, 1.0);
}

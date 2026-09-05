#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <ephemeris:noise.glsl>

in vec4 vertexColor;
in vec2 acrossThePage;

out vec4 fragColor;

// How wide a bank of mist is, in pixels of the page.
const float BANK = 26.0;

// How far the field moves per unit of `GameTime`, which runs nought to one across a day.
const float DRIFT = 1350.0;

// How far the field drags itself about. This is the whole difference between mist that curls and a
// texture that slides: the offset a sample is read at is itself noise, so banks fold into each other
// instead of travelling in a straight line.
const float CURL = 1.7;

// Far enough apart that the tiling never comes round inside a panel.
const vec2 FIELD = vec2(96.0);

// Greyscale, and never quite black or white: mist is what you cannot see through, not an absence of light.
const float DARKEST = 0.10;
const float LIGHTEST = 0.66;

void main() {
    vec2 at = acrossThePage / BANK;
    float phase = GameTime * DRIFT;

    vec2 pull = vec2(
        ephemerisFbm(at + vec2(phase * 0.05, 0.0), FIELD, 3),
        ephemerisFbm(at + vec2(0.0, phase * 0.04) + 11.3, FIELD, 3)
    );
    float bank = ephemerisFbm(at + pull * CURL + phase * 0.02, FIELD, 4);

    float shade = mix(DARKEST, LIGHTEST, smoothstep(0.30, 0.78, bank));
    fragColor = vec4(vec3(shade), vertexColor.a) * ColorModulator;
}

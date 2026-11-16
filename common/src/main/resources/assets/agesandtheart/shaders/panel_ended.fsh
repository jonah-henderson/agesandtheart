#version 330
// SPIR-V since 26.3: every stage-crossing declaration needs a location.
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <ephemeris:noise.glsl>

// The panel of an Age an end has struck, as Riven's book shows it in Exile: dark cells, bits of their walls winking
// with light, and flares ebbing and flowing in eddies through the smoke, where the walls' white-hot cores grow into
// the cells they border until the cells are all core, a glare crackling as the walls do, lighting the smoke round it. Laid out in page-pixels,
// as the mist is, so it is the same in a book and on a lectern.
//
// Everything that moves goes a whole number of its field's periods in one turn of the clock, which runs nought to
// one across twenty real minutes, so nothing jumps when the clock comes round.

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 acrossThePage;

layout(location = 0) out vec4 fragColor;

// How wide a cell is, in page-pixels, and the cells' tiling.
const float CELL = 5.5;
const vec2 CELLS = vec2(32.0);

const float TAU = 6.2831853;

// How far each cell's heart wanders from its middle, and how many times round it goes in a turn of the clock.
const float WANDER = 0.38;
const float WANDERS = 2000.0;

// How far the whole sheet slides in a turn of the clock, in cells: a whole number of the cells' tiling, and of the
// smoke's at its spread, so neither jumps when the clock comes round.
const vec2 SLIDES = vec2(320.0, -320.0);

// How hard the cells are dragged out of shape, so the walls jag as lightning does: by a field drawn afresh [DRAGS]
// times a turn of the clock, itself pulled about by a slower one [SWIRL] hard, so the cells writhe and eddy in place.
const float WARP = 0.9;
const float DRAGS = 900.0;
const float SWIRLS = 400.0;
const float SWIRL = 1.2;
const float JAG = 0.10;
const vec2 JAG_FIELD = vec2(64.0);
const float JAG_CHURN = 512.0;

// Bits of wall winking: how fast each wall flickers and crackles, how much it varies along its length, and how few
// are lit at once.
const float FLICKERS = 4000.0;
const float CRACKLES = 12000.0;
const float SEGMENTS = 1.6;
const vec2 SEGMENT_FIELD = vec2(64.0);
const float SEGMENT_CHURN = 2048.0;
const float SEGMENT_DEPTH = 0.35;
const float LIT_FROM = 0.78;
const float LIT_BY = 0.95;

// The flares: the peaks of a field that changes where it stands rather than sliding, pulled about by a second such
// field so its patches stretch and curl as eddies in smoke. [FLOWS] and [EDDIES] are how many times each is drawn
// afresh in a turn of the clock; between draws it blends. Where the field passes [FIELD_FROM] a flare begins, and
// the walls' cores grow by its reach, in cells: past the middle of any cell at its full.
const float FLARE_SCALE = 14.0;
const float FLOWS = 4000.0;
const float EDDIES = 2000.0;
const float EDDY = 1.6;
const vec2 FLARE_FIELD = vec2(32.0);
const float FIELD_FROM = 0.73;
const float FIELD_BY = 0.88;
const float FILLS = 1.4;
const float FLARE_CRACKLE = 0.25;

// The smoke between them.
const float SMOKE_SPREAD = 0.35;
const vec2 SMOKE_FIELD = vec2(16.0);
const float SMOKE_DRIFT = 64.0;

// How far a wall's core and its halo reach from the wall, in cells.
const float CORE = 0.016;
const float HALO = 0.09;

const vec3 DARK = vec3(0.006, 0.006, 0.010);
const vec3 SMOKE = vec3(0.045, 0.058, 0.090);
const vec3 LIT_SMOKE = vec3(0.75, 0.42, 0.22);
const vec3 WHITE_HOT = vec3(1.0, 0.95, 0.88);
const vec3 EMBER = vec3(0.95, 0.50, 0.20);

// What a wall's core grows into: orange at a flare's front and near white at its heart, taken [GLARE] of the way
// toward the wall's own white-hot core, which is brighter than white and so glares flat.
const vec3 GROWING = vec3(1.0, 0.55, 0.25);
const vec3 GROWN = vec3(1.0, 0.90, 0.78);
const float GLARE = 0.75;

// A field of noise at [at], tiling at [period], drawn afresh [turns] times in a turn of the clock and blended straight
// between draws, so it changes where it stands and never lingers at a draw. The blend is stretched back to the spread of one draw, or it would dim between them.
float evolving(vec2 at, float clock, float turns, float salt, vec2 period, int octaves) {
    float stepping = clock * turns;
    float moment = floor(stepping);
    float between = stepping - moment;
    vec2 drawing = vec2(turns, 64.0);
    vec2 here = 97.0 * vec2(ephemerisCell(vec2(moment, salt), drawing), ephemerisCell(vec2(moment, salt + 1.0), drawing));
    vec2 next = 97.0 * vec2(ephemerisCell(vec2(moment + 1.0, salt), drawing), ephemerisCell(vec2(moment + 1.0, salt + 1.0), drawing));
    float blended = mix(ephemerisFbm(at + here, period, octaves), ephemerisFbm(at + next, period, octaves), between);
    return (blended - 0.5) / sqrt(between * between + (1.0 - between) * (1.0 - between)) + 0.5;
}

// Value noise that runs in a straight line from one drawn value to the next along x, the clock, and eases only across
// y. Eased in time it lingers at every drawn value, which reads as a pulse; straight, nothing ever pauses.
float straightInTime(vec2 at, vec2 period) {
    vec2 cell = floor(at);
    vec2 into = fract(at);
    float across = into.y * into.y * (3.0 - 2.0 * into.y);
    float lowLeft = ephemerisCell(cell, period);
    float lowRight = ephemerisCell(cell + vec2(1.0, 0.0), period);
    float highLeft = ephemerisCell(cell + vec2(0.0, 1.0), period);
    float highRight = ephemerisCell(cell + vec2(1.0, 1.0), period);
    return mix(mix(lowLeft, lowRight, into.x), mix(highLeft, highRight, into.x), across);
}

// [at] from [from] to [to], straight, held at nought and one beyond them.
float rampOf(float from, float to, float at) {
    return clamp((at - from) / (to - from), 0.0, 1.0);
}

// One cell's heart, circling [WANDER] from the cell's middle [WANDERS] times a turn of the clock.
vec2 heartOf(vec2 cell, float clock, out float drawn) {
    drawn = ephemerisCell(cell, CELLS);
    float drawnAgain = ephemerisCell(cell + vec2(11.0, 23.0), CELLS);
    float turned = clock * WANDERS;
    return cell + 0.5 + WANDER * vec2(sin(TAU * (turned + drawn)), cos(TAU * (turned + drawnAgain)));
}

void main() {
    // The clock rides in the vertex colour's red and green as a high and a low byte, as the mist's does.
    float clock = (vertexColor.r * 255.0 * 256.0 + vertexColor.g * 255.0) / 65535.0;
    // The whole sheet sliding, so the cells looked at are never the same ones for long.
    vec2 slid = acrossThePage + clock * SLIDES * CELL;
    vec2 at = slid / CELL;

    vec2 swirl = vec2(
        evolving(at * 0.5, clock, SWIRLS, 50.0, CELLS, 2),
        evolving(at * 0.5 + vec2(17.0, 0.0), clock, SWIRLS, 60.0, CELLS, 2)
    ) - 0.5;
    vec2 dragAt = at * 0.9 + swirl * SWIRL;
    vec2 drag = vec2(
        evolving(dragAt, clock, DRAGS, 40.0, CELLS, 3),
        evolving(dragAt + vec2(3.7, 0.0), clock, DRAGS, 45.0, CELLS, 3)
    ) - 0.5;
    vec2 warped = at + drag * WARP;

    // The nearest two cells' hearts: a wall is where they are as near as each other.
    vec2 home = floor(warped);
    float nearest = 9.0;
    float second = 9.0;
    float nearestCell = 0.0;
    float secondCell = 0.0;
    for (int across = -1; across <= 1; across++) {
        for (int down = -1; down <= 1; down++) {
            float drawn;
            vec2 heart = heartOf(home + vec2(float(across), float(down)), clock, drawn);
            float away = length(heart - warped);
            if (away < nearest) {
                second = nearest;
                secondCell = nearestCell;
                nearest = away;
                nearestCell = drawn;
            } else if (away < second) {
                second = away;
                secondCell = drawn;
            }
        }
    }
    float jag = ephemerisNoise(warped * 6.0 + vec2(clock * JAG_CHURN, 0.0), JAG_FIELD) - 0.5;
    float fromTheWall = max(second - nearest + jag * JAG, 0.0);

    float wall = fract((nearestCell + secondCell) * 7.13);
    float segment = ephemerisNoise(warped * SEGMENTS + vec2(clock * SEGMENT_CHURN, 0.0), SEGMENT_FIELD) - 0.5;
    float flicker = straightInTime(vec2(clock * FLICKERS, wall * 64.0), vec2(FLICKERS, 64.0)) + segment * SEGMENT_DEPTH;
    float crackle = 0.55 + 0.45 * straightInTime(vec2(clock * CRACKLES, wall * 64.0 + 7.0), vec2(CRACKLES, 64.0));
    float wallLit = rampOf(LIT_FROM, LIT_BY, flicker) * crackle;

    // The flares, where the eddying field peaks.
    vec2 flareAt = slid / FLARE_SCALE;
    vec2 eddy = vec2(
        evolving(flareAt * 0.7, clock, EDDIES, 10.0, FLARE_FIELD, 3),
        evolving(flareAt * 0.7 + vec2(31.0, 0.0), clock, EDDIES, 20.0, FLARE_FIELD, 3)
    ) - 0.5;
    float field = evolving(flareAt + eddy * EDDY, clock, FLOWS, 30.0, FLARE_FIELD, 4);
    float flareCrackle = 1.0 - FLARE_CRACKLE + FLARE_CRACKLE * straightInTime(vec2(clock * CRACKLES, 7.0), vec2(CRACKLES, 64.0));
    float flare = rampOf(FIELD_FROM, FIELD_BY, field) * flareCrackle;
    float haze = rampOf(FIELD_FROM - 0.12, FIELD_BY, field);

    // In a flare the walls' cores grow into the cells by its reach, so near its heart the cells are all core.
    float reach = flare * FILLS;
    float shrunk = max(fromTheWall - reach, 0.0);
    float lit = max(wallLit, min(flare * 3.0, 1.0));
    float wallCore = exp(-fromTheWall / CORE);
    float grown = clamp(exp(-shrunk / CORE) - wallCore, 0.0, 1.0);
    float halo = exp(-shrunk / HALO);
    vec3 graded = mix(GROWING, GROWN, clamp(flare * 1.6, 0.0, 1.0)) * 0.95;
    vec3 grownColour = mix(graded, WHITE_HOT * 1.3, GLARE);
    float smoke = smoothstep(0.48, 0.85, ephemerisFbm(at * SMOKE_SPREAD + vec2(clock * SMOKE_DRIFT, 0.0), SMOKE_FIELD, 4));

    vec3 colour = DARK
        + SMOKE * smoke * 0.9
        + LIT_SMOKE * (smoke * 0.5 + 0.06) * haze
        + WHITE_HOT * wallCore * lit * 1.3
        + EMBER * halo * lit * 0.40
        + grownColour * grown * lit;
    fragColor = vec4(min(colour, vec3(1.0)), vertexColor.a) * ColorModulator;
}

package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.Canyon
import co.voik.agesandtheart.worldgen.field.Palette
import co.voik.agesandtheart.worldgen.field.bearingNamed
import co.voik.agesandtheart.worldgen.field.SeaFill
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Weathered
import co.voik.agesandtheart.worldgen.carver.Weathering
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.block.Blocks

/**
 * Rock from the bedrock to the height limit, with one canyon cut through the origin — **the world is the
 * absence**, which is the reverse of every other preset here.
 *
 * Two consequences of filling to the ceiling, both deliberate and both visible the moment you arrive.
 * `getBaseHeight` answers truthfully, so the plateau's surface *is* [WORLD_CEILING]: vanilla paints its
 * surface rules there, grows its features there and places its structures there. And a field Age's biomes
 * carry no carvers, so the rock is solid — the canyon is the only open space in the world.
 *
 * The river needs no mechanism of its own. [SeaFill] fills whatever the shape leaves empty below its
 * level, and in a world solid everywhere else the only empty space down there is the canyon floor — so a
 * waterline just above [FLOOR_Y] puts a river along the gorge and nowhere else, following the meander for
 * free.
 */
object CanyonField {

    /**
     * The world, weathered — **not an option a writer takes**, unlike the Spire's.
     *
     * A terraced cut is a set of ruled contours until something breaks them, and what breaks them is not
     * separable from the landform: an unweathered canyon does not read as a plainer canyon, it reads as
     * machined. So the wind is part of the shape here rather than something `weathered` adds to it.
     */
    fun world(bearing: String, salt: Long = 0L): TerrainField =
        Weathered.sculpting(bareWorld(bearing, salt), Weathering.CANYON, SHELTER_REACH, WORLD_CEILING)

    /** The cut before the weather reaches it — the previewer's other half, and nothing else's. */
    fun bareWorld(bearing: String, salt: Long = 0L): TerrainField =
        Canyon.cut(ground(), listOf(canyon(bearingNamed(bearing), salt)))

    /** Bedrock to the ceiling, everywhere. */
    fun ground(): TerrainField = Slab(lowY = WORLD_FLOOR, highY = WORLD_CEILING)

    /**
     * One canyon of this Age's size, on a [bearing] and [offset] of its own.
     *
     * Taken apart from [world] because the shape is meant to be reused: a land criss-crossed by many is
     * this canyon [Canyon.resized] smaller, several times over, and the numbers should not be written
     * twice to get there.
     */
    fun canyon(bearing: Double, salt: Long = 0L, offset: Double = 0.0) = Canyon(
        bearing = bearing,
        offset = offset,
        halfWidth = HALF_WIDTH,
        floorY = FLOOR_Y,
        // One past the ceiling, so the rim is met rather than shaved.
        rimY = WORLD_CEILING + 1,
        seed = MEANDER_SEED xor salt,
        // Both are lengths, so they are taken from the width rather than left at the node's defaults —
        // which is what keeps a canyon of any size meandering in proportion to itself.
        meanderReach = HALF_WIDTH * MEANDER_SHARE_OF_WIDTH,
        meanderStretch = HALF_WIDTH * BEND_SHARE_OF_WIDTH,
    )

    fun generator(biomeSource: BiomeSource): AgeChunkGenerator =
        AgeChunkGenerator(
            biomeSource,
            world(bearing = "north_south"),
            SeaFill.of(Blocks.WATER.defaultBlockState(), level = RIVER_LEVEL),
            Palette.BARE_ROCK,
        )

    /** The lowest block of [VerticalWindow.DEFAULT], which this world is solid all the way down to. */
    const val WORLD_FLOOR = -64

    /** The topmost block of [VerticalWindow.DEFAULT], which this world is solid all the way up to. */
    const val WORLD_CEILING = 319

    /** How much rock is left under the deepest the river runs. */
    private const val ROOM_UNDER_THE_RIVER = 16

    /** How deep the river stands over the mean bed. */
    private const val RIVER_DEPTH = 6

    /**
     * The **mean** bed level at the axis — sixteen blocks over [WORLD_FLOOR], which is the room left under
     * the river for a mine, a cave or anything else that wants to be beneath the world. The bed itself
     * rises and falls either side of it; see [Canyon.bedRelief].
     */
    const val FLOOR_Y = WORLD_FLOOR + ROOM_UNDER_THE_RIVER

    /**
     * A few blocks over [FLOOR_Y], so the river sits in the valley bed rather than drowning the gorge.
     * Read against [Canyon.DEFAULT_BED_RELIEF]: the bed rises past this in places, which is what leaves
     * bars and shallows standing out of the water instead of one even sheet.
     */
    const val RIVER_LEVEL = FLOOR_Y + RIVER_DEPTH

    /**
     * Half the canyon's width, so about 720 blocks across against a 367-block drop — a shade under 1:2.
     * Still far steeper than the real thing, which runs nearer 1:10: at a true ratio the far rim would sit
     * past any render distance and the canyon would read as the edge of the world rather than as a canyon.
     *
     * **It moves with [FLOOR_Y]**, that ratio being the thing chosen rather than either number.
     *
     * **Untuned by eye** — `./gradlew :common:preview --args=canyon` draws the cross-section, and the
     * slice across the bearing is the one to read.
     */
    const val HALF_WIDTH = 360.0

    /**
     * How far into a canyon wall the weather works. Deep enough that a soft band can be hollowed into a
     * real alcove rather than pitted, and shallow enough to leave the mass behind it whole.
     */
    private const val SHELTER_REACH = 36

    /** How far the axis wanders, against the width — enough that a bend is plainly a bend. */
    private const val MEANDER_SHARE_OF_WIDTH = 0.35

    /** And how long one bend runs. Shorter than the canyon is wide, so a bend is not a whole world away. */
    private const val BEND_SHARE_OF_WIDTH = 0.2

    private const val MEANDER_SEED = 0xCA_1907L
}

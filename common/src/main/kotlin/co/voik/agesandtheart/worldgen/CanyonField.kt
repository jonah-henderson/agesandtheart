package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.Canyon
import co.voik.agesandtheart.worldgen.field.SeaFill
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Weathered
import kotlin.math.roundToInt

/**
 * Rock from the bedrock to a plateau — at `colossal`, the height limit — with one canyon cut through the
 * origin: **the world is the absence**, which is the reverse of every other preset here.
 *
 * Two consequences of filling to the ceiling, both deliberate and both visible the moment you arrive.
 * `getBaseHeight` answers truthfully, so the plateau's surface *is* [VerticalWindow.HIGHEST_BLOCK_Y]: vanilla
 * paints its surface rules there, grows its features there and places its structures there. And a field Age's biomes
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
    /**
     * The angle a line runs at when nobody said — north to south, which is where a zero bearing points.
     *
     * A number rather than a word: translating an axis into an angle is `age.aspect`'s, and reaching for
     * it here is what put the Art's vocabulary underneath the field toolkit.
     */
    private const val NORTH_TO_SOUTH = 0.0

    /**
     * [scale] is [SizeScale]'s factor, and the canyon as tuned is `colossal`. Everything takes a quarter of
     * the factor — width, meander, depth and the weather's grain together. The big ones keep the tuned
     * floor and come down from the ceiling; the small ones keep a plateau at [LEAST_PLATEAU_Y] and their
     * floor rises to meet it, so they are cut into ordinary ground rather than into the deepslate. The
     * river follows the floor either way ([riverLevel]).
     */
    fun world(bearing: Double = NORTH_TO_SOUTH, salt: Long = 0L, scale: Double = SizeScale.ORDINARY): TerrainField {
        val share = scale / SizeScale.COLOSSAL
        return Weathered.sculpting(
            bareWorld(bearing, salt, scale),
            Weathering.CANYON.resized(share, pivotY(scale)),
            (SHELTER_REACH * share).roundToInt().coerceAtLeast(1),
            plateauY(scale),
        )
    }

    /** The cut before the weather reaches it — the previewer's other half, and nothing else's. */
    fun bareWorld(bearing: Double = NORTH_TO_SOUTH, salt: Long = 0L, scale: Double = SizeScale.ORDINARY): TerrainField =
        Canyon.cut(ground(scale), listOf(canyon(bearing, salt, scale = scale)))

    /** Bedrock to the plateau, everywhere — which at `colossal` is the ceiling. */
    fun ground(scale: Double = SizeScale.ORDINARY): TerrainField =
        Slab(lowY = VerticalWindow.MIN_Y, highY = plateauY(scale))

    /** The top of the rock the canyon is cut into. */
    fun plateauY(scale: Double): Int = maxOf(FLOOR_Y + depth(scale), LEAST_PLATEAU_Y)

    /** The mean bed at the axis — [FLOOR_Y] for a canyon deep enough to reach it. */
    fun floorY(scale: Double): Int = plateauY(scale) - depth(scale)

    /** Where the river stands, which is the Age's waterline: [RIVER_LEVEL] at the tuned floor. */
    fun riverLevel(scale: Double): Int = floorY(scale) + RIVER_DEPTH

    private fun depth(scale: Double): Int =
        ((VerticalWindow.HIGHEST_BLOCK_Y - FLOOR_Y) * scale / SizeScale.COLOSSAL).roundToInt()

    /**
     * The height the tuned canyon is resized about so that its floor lands on [floorY] — which is [FLOOR_Y]
     * itself for every canyon deep enough to keep it.
     */
    private fun pivotY(scale: Double): Int {
        val share = scale / SizeScale.COLOSSAL
        if (share >= 1.0) return FLOOR_Y
        return ((floorY(scale) - FLOOR_Y * share) / (1.0 - share)).roundToInt()
    }

    /**
     * One canyon of this Age's size, on a [bearing] and [offset] of its own.
     *
     * Taken apart from [world] because the shape is meant to be reused: a land criss-crossed by many is
     * this canyon [Canyon.resized] smaller, several times over, and the numbers should not be written
     * twice to get there.
     */
    fun canyon(bearing: Double, salt: Long = 0L, offset: Double = 0.0, scale: Double = SizeScale.ORDINARY) =
        tunedCanyon(bearing, salt)
            .resized(scale / SizeScale.COLOSSAL, pivotY(scale))
            // Exactly, where the resize would round: the river is set against the floor.
            .copy(offset = offset, floorY = floorY(scale), rimY = plateauY(scale) + 1)

    private fun tunedCanyon(bearing: Double, salt: Long) = Canyon(
        bearing = bearing,
        offset = 0.0,
        halfWidth = HALF_WIDTH,
        floorY = FLOOR_Y,
        // One past the ceiling, so the rim is met rather than shaved.
        rimY = VerticalWindow.TOP_Y,
        seed = MEANDER_SEED xor salt,
        // Both are lengths, so they are taken from the width rather than left at the node's defaults —
        // which is what keeps a canyon of any size meandering in proportion to itself.
        meanderReach = HALF_WIDTH * MEANDER_SHARE_OF_WIDTH,
        meanderStretch = HALF_WIDTH * BEND_SHARE_OF_WIDTH,
    )

    /**
     * The lowest a canyon's plateau stands: a little over vanilla's ground, so a small canyon is a gorge in
     * ordinary country. The `large` canyon is the first deep enough to need more.
     */
    private const val LEAST_PLATEAU_Y = 100

    /** How much rock is left under the deepest the river runs. */
    private const val ROOM_UNDER_THE_RIVER = 16

    /** How deep the river stands over the mean bed. */
    private const val RIVER_DEPTH = 6

    /**
     * The **mean** bed level at the axis — sixteen blocks over [VerticalWindow.MIN_Y], which is the room left
     * under the river for a mine, a cave or anything else that wants to be beneath the world. The bed itself
     * rises and falls either side of it; see [Canyon.bedRelief].
     */
    const val FLOOR_Y = VerticalWindow.MIN_Y + ROOM_UNDER_THE_RIVER

    /**
     * A few blocks over [FLOOR_Y], so the river sits in the valley bed rather than drowning the gorge.
     * Read against [Canyon.DEFAULT_BED_RELIEF]: the bed rises past this in places, which is what leaves
     * bars and shallows standing out of the water instead of one even sheet.
     */
    const val RIVER_LEVEL = FLOOR_Y + RIVER_DEPTH

    /**
     * Half a `colossal` canyon's width, so about 720 blocks across against a 367-block drop — a shade under 1:2.
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

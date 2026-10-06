package co.voik.agesandtheart.worldgen

import kotlin.math.roundToInt
import co.voik.agesandtheart.worldgen.field.Subtract
import co.voik.agesandtheart.worldgen.field.NoiseHeightmap
import co.voik.agesandtheart.worldgen.field.Cone
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.Density
import co.voik.agesandtheart.worldgen.field.Grid
import co.voik.agesandtheart.worldgen.field.Instanced
import co.voik.agesandtheart.worldgen.field.Intersect
import co.voik.agesandtheart.worldgen.field.Noise3D
import co.voik.agesandtheart.worldgen.field.NoiseCharacter
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Union
import co.voik.agesandtheart.worldgen.field.Variation
import co.voik.agesandtheart.worldgen.field.Warped

/**
 * Islands floating in nothing, all about one height, each an outline of ordinary ground over an underside
 * that tapers away beneath it — overworld country cut loose. There is no ground and no sea below: fall, and
 * there is only the void.
 *
 * **The tops are one world-anchored surface**, graded 3D noise as [OverworldField] builds its land, and each
 * island is the part of it that falls inside its outline. So the hills run across an island as they would
 * across a continent, rather than every island wearing the same cap.
 *
 * **Roughly one plane, on purpose.** What sets this apart from the Spire's islands is that one may walk,
 * bridge or jump from island to island, so their ground all stands on [PLANE_Y] and only their hills
 * differ, rather than stacking up the sky.
 *
 * **The noise sits outside the instancing**, as it did in this construction's first use: every island is
 * one template, and cutting it from a world-anchored field is what makes each come out a different shape.
 */
object SkylandsField {

    /**
     * [scale] is [SizeScale]'s factor: the islands, their spacing and the grain of their wear are resized
     * together about the plane they float at.
     */
    fun world(salt: Long = 0L, scale: Double = SizeScale.ORDINARY): TerrainField =
        Subtract(islands(salt), riverValleys(salt)).resized(scale, PLANE_Y)

    /**
     * The water in the islands' rivers (Jonah, 2026-10-06: "about the same feel as vanilla's rivers"): long
     * meandering courses across the whole sky, flat, one block under the ground they cut through, as a
     * vanilla river lies under its banks. Where one meets an island's rim it pours off into the void.
     */
    fun rivers(salt: Long = 0L, scale: Double = SizeScale.ORDINARY): TerrainField =
        Intersect(
            listOf(
                riverValleys(salt),
                Slab(lowY = PLANE_Y - RIVER_DEPTH, highY = PLANE_Y - 1),
                islands(salt),
            ),
        ).resized(scale, PLANE_Y)

    /**
     * Where a river is cut out of the islands: everything above two surfaces drawn from one ridged noise,
     * which dips along the noise's zero line — a line that wanders for hundreds of blocks, as a river does.
     *
     * The **bed** is a shallow trough, [RIVER_DEPTH] under the plane on the river's line and meeting the plane
     * at its edge. The **banks** are far steeper, crossing the plane at that same edge: beside the river they
     * cut its valley through whatever hills stand there, and away from it they are far above everything.
     * The cut is above both, so the bed rules in the channel and the banks out of it.
     */
    private fun riverValleys(salt: Long): TerrainField {
        fun ridged(lowest: Double, rise: Double): NoiseHeightmap {
            // A ridged sample is 1 on the line and falls by 2 for each unit of noise away from it, so a
            // negative relief of half the rise puts the surface at [lowest] on the line, climbing [rise] a unit.
            val baseY = (lowest + rise / 2.0).roundToInt()
            return NoiseHeightmap(
                seed = RIVER_SEED xor salt,
                firstOctave = RIVER_OCTAVE,
                amplitudes = RIVER_AMPLITUDES,
                scaleX = RIVER_SCALE,
                scaleZ = RIVER_SCALE,
                baseY = baseY,
                relief = -rise / 2.0,
                // Above the base, so the heightmap faces down and carves rather than standing up from a ceiling.
                flatY = maxOf(VerticalWindow.HIGHEST_BLOCK_Y, baseY + 1),
                character = NoiseCharacter.RIDGED,
            )
        }
        val bedRise = RIVER_DEPTH / RIVER_HALF_WIDTH
        val bed = ridged(lowest = (PLANE_Y - RIVER_DEPTH).toDouble(), rise = bedRise)
        val banks = ridged(lowest = PLANE_Y + 1 - BANK_RISE * RIVER_HALF_WIDTH, rise = BANK_RISE)
        return Intersect(listOf(bed, banks))
    }

    private fun islands(salt: Long): TerrainField {
        val outlines = Instanced(
            templates = LOBES.map(::island),
            placement = Grid(spacing = SPACING, jitter = JITTER, density = Density.uniform()),
            variation = Variation(
                yawSteps = 1,
                minScale = SMALLEST_ISLAND,
                maxScale = LARGEST_ISLAND,
                scaleSteps = ISLAND_SIZES,
                pivotY = PLANE_Y,
            ),
            seed = LAYOUT_SEED xor salt,
        )
        // The country the islands are cut from: solid under the plane, hills and hollows over it.
        val country = Union(listOf(Slab(lowY = VerticalWindow.MIN_Y, highY = PLANE_Y), surface(salt)))
        // Frets the undersides without eating an island whole, and stops short of the plane.
        val wear = Noise3D(
            seed = WEAR_SEED xor salt,
            firstOctave = -5,
            amplitudes = listOf(1.0, 0.5, 0.25),
            scaleX = WEAR_SCALE,
            scaleY = WEAR_SCALE,
            scaleZ = WEAR_SCALE,
            character = NoiseCharacter.PLAIN,
            threshold = WEAR_AT_THE_KEEL,
            lowY = BAND_LOW_Y,
            highY = PLANE_Y,
            // Graded so the keel is worn most and the rock nearest the ground above hardly at all.
            thresholdAtTop = WEAR_AT_THE_TOP,
        )
        val unworn = Slab(lowY = PLANE_Y + 1, highY = VerticalWindow.HIGHEST_BLOCK_Y)
        // Warped last, so the rims go ragged while every top stays as level as it was.
        val ragged = Warped(
            base = Intersect(listOf(outlines, country, Union(listOf(wear, unworn)))),
            seed = RIM_SEED xor salt,
            firstOctave = -4,
            amplitudes = listOf(1.0, 0.5),
            scale = RIM_SCALE,
            amount = RIM_AMOUNT,
        )
        return ragged
    }

    /**
     * The ordinary ground every island's top is cut from — [OverworldField]'s construction at an island's
     * scale: hills a few dozen blocks across rising from the plane, and nothing above [TOP_Y].
     */
    private fun surface(salt: Long): Noise3D = Noise3D(
        seed = LAND_SEED xor salt,
        firstOctave = -6,
        amplitudes = listOf(1.0, 0.6, 0.35),
        scaleX = LAND_SCALE,
        scaleY = LAND_SCALE,
        scaleZ = LAND_SCALE,
        character = NoiseCharacter.PLAIN,
        threshold = SOLID_AT_THE_PLANE,
        lowY = PLANE_Y,
        highY = TOP_Y,
        thresholdAtTop = EMPTY_AT_THE_TOP,
    )

    /**
     * One island's outline: a lobe at each of [lobes], every one a cone widest at the top of the country and
     * tapering to a point far beneath it. **The ground runs to the edge**: wherever the country's surface
     * meets the cone's side is the island's rim, and the side carries on down as its underside rather than
     * standing a wall between the two.
     */
    private fun island(lobes: List<Lobe>): TerrainField = Union(
        lobes.map { lobe ->
            Cone(
                baseX = lobe.x,
                baseZ = lobe.z,
                baseRadius = TOP_RADIUS * lobe.share,
                baseY = TOP_Y,
                tipY = PLANE_Y - (UNDERSIDE_DEPTH * lobe.share).toInt(),
            )
        },
    )

    /** Where a lobe sits in its island, and how large it is against a whole one. */
    private data class Lobe(val x: Int, val z: Int, val share: Double)

    /** Three outlines, so no two neighbours need be the same round shape: one lobe, two, and three. */
    private val LOBES = listOf(
        listOf(Lobe(0, 0, 1.0)),
        listOf(Lobe(-14, 0, 0.8), Lobe(18, 6, 0.65)),
        listOf(Lobe(0, -12, 0.7), Lobe(-16, 12, 0.6), Lobe(17, 10, 0.55)),
    )

    /** The height the islands' ground stands on, with their hills over it. */
    const val PLANE_Y = 120

    /** Where the clouds lie: under the islands, near where their undersides end. */
    const val CLOUDS_Y = 72

    /**
     * Where a sea stands when a book names one: far under the islands, about thirty blocks below the
     * deepest of their undersides, so it is an ocean seen through the clouds rather than one they sit in.
     */
    const val NAMED_SEA_LEVEL = 30

    /** A lobe's radius at [TOP_Y]; at the height its ground usually stands it is about three quarters of this. */
    private const val TOP_RADIUS = 52.0

    /** The highest an island's hills reach. */
    private const val TOP_Y = PLANE_Y + 48
    private const val UNDERSIDE_DEPTH = 60

    /** Far enough apart that a gap is a crossing, near enough that the next island is in sight. */
    private const val SPACING = 120.0
    private const val JITTER = 30.0

    private const val SMALLEST_ISLAND = 0.45
    private const val LARGEST_ISLAND = 1.5
    private const val ISLAND_SIZES = 6

    /** The wear's reach, which has to cover the largest island lifted as far as it goes. */
    private const val BAND_LOW_Y = PLANE_Y - (UNDERSIDE_DEPTH * 2) - 1


    // About a third of an island's radius, so the wear cuts coves and ribs rather than holes through it.
    private const val WEAR_SCALE = 0.6
    private const val WEAR_AT_THE_KEEL = -0.1
    private const val WEAR_AT_THE_TOP = -0.7

    /** Hills a few dozen blocks across, which is what an island's worth of overworld holds. */
    private const val LAND_SCALE = 0.7

    // Nearly all solid at the plane, so every island has ground at its rim, and nothing left at the top.
    private const val SOLID_AT_THE_PLANE = -0.9
    private const val EMPTY_AT_THE_TOP = 1.0

    /** How far a rim is pushed in or out, and over how long a stretch of it — enough for coves and spurs. */
    private const val RIM_SCALE = 3.0
    private const val RIM_AMOUNT = 22.0

    /** How deep a river runs under the plane on its line, and how much noise either side of it is river. */
    private const val RIVER_DEPTH = 4
    private const val RIVER_HALF_WIDTH = 0.02

    /** How fast a river's banks climb away from it, in blocks for each unit of noise. */
    private const val BANK_RISE = 250.0

    /** A river's noise: long wavelengths, so one course runs on across many islands. */
    private const val RIVER_OCTAVE = -8
    private val RIVER_AMPLITUDES = listOf(1.0, 0.5)
    private const val RIVER_SCALE = 1.0

    private const val RIVER_SEED = 0x5C1_4B2DL
    private const val RIM_SEED = 0x5C1_41A1L
    private const val LAND_SEED = 0x5C1_1A4DL
    private const val LAYOUT_SEED = 0x5C1_1A2DL
    private const val WEAR_SEED = 0x5C1_3EA2L
}

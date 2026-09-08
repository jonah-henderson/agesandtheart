package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.age.aspect.Span
import co.voik.agesandtheart.worldgen.field.Density
import co.voik.agesandtheart.worldgen.field.Ellipsoid
import co.voik.agesandtheart.worldgen.field.Grid
import co.voik.agesandtheart.worldgen.field.Instanced
import co.voik.agesandtheart.worldgen.field.Intersect
import co.voik.agesandtheart.worldgen.field.NoiseHeightmap
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.Subtract
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Union
import co.voik.agesandtheart.worldgen.field.Variation

/**
 * An **underground of great chambers, each with a lake in the bottom of it** — the vaults a city could be
 * built in, and the counterpart to [GreatHalls]: one is a thing somebody made, this is a thing that
 * happened.
 *
 * Nothing here is a new kind of node. The chambers are an [Instanced] grid of [Ellipsoid]s, the floor is a
 * [NoiseHeightmap] subtracted from them, and the lake is the part of what is left below [lakeLevel]. The
 * floor is sampled in **world** space rather than per chamber, so every chamber gets a different piece of
 * it and the islands that rise through the water are never twice the same.
 *
 * **The chambers sit in a band centred on the water rather than spread through the rock**, which is what
 * makes the lakes dependable: a chamber lifted clear of the level would be a dry vault and one sunk under
 * it a drowned one, so the lift is small against the height and every chamber comes out with water in its
 * bottom and air over it.
 *
 * **The origin cell is a chamber, and that is arrival rather than generosity.** A [Grid] lays cell zero on
 * the world origin bar its jitter, which is small against a chamber's radius — so a writer who asked for
 * chambers arrives in one instead of inside the rock between two.
 */
object Chambers {

    /**
     * The volume to take out of an Age's rock between [floorY] and [roofY], at the [size] asked for.
     *
     * Empty where the band cannot hold a chamber worth the name — the same answer [GreatHalls] gives a
     * landform with no room, and the reason a shallow underground is simply not chambered rather than
     * being chambered badly.
     */
    fun voidBetween(floorY: Int, roofY: Int, size: Double? = null, salt: Long = 0L): TerrainField {
        val plan = planFor(floorY, roofY, size) ?: return Union(emptyList())
        return hollow(plan, salt)
    }

    /**
     * The water standing in those chambers: everything hollow below [lakeLevel] and nothing else.
     *
     * **Bounded to the chambers rather than poured at a level**, because the level is the chambers' own and
     * not the Age's. A flat fill at this height would find every cave in the world as well, and an Age's
     * own waterline is somewhere else entirely — see `Terrain.Ground.wet`.
     */
    fun lakesIn(floorY: Int, roofY: Int, size: Double? = null, salt: Long = 0L): TerrainField {
        val plan = planFor(floorY, roofY, size) ?: return Union(emptyList())
        return Intersect(listOf(Slab(lowY = floorY, highY = plan.lakeLevel), hollow(plan, salt)))
    }

    /**
     * The vaults, floored and **bounded to the band they were asked for**.
     *
     * The bound is not tidiness: a lens is far taller than the dome that survives its floor, and the floor
     * is a heightmap that reaches back only to its own flat bound — so under that bound the lens is
     * subtracted from nothing and hangs on as a slab of void beneath the bed it is supposed to stand on.
     */
    private fun hollow(plan: Plan, salt: Long): TerrainField = Intersect(
        listOf(
            Slab(lowY = plan.floorY, highY = plan.roofY),
            Subtract(chambers(plan, salt), floor(plan, salt)),
        ),
    )

    /** Where the water stands in a band of this shape, or null where the band holds no chamber at all. */
    fun lakeLevel(floorY: Int, roofY: Int, size: Double? = null): Int? = planFor(floorY, roofY, size)?.lakeLevel

    /** The chambers themselves, before their floor is put back under them. */
    private fun chambers(plan: Plan, salt: Long): TerrainField = Instanced(
        templates = listOf(
            Ellipsoid(
                centerX = 0,
                centerY = plan.bedY,
                centerZ = 0,
                radiusXZ = plan.radius,
                radiusY = plan.height,
            ),
        ),
        placement = Grid(
            spacing = plan.spacing,
            jitter = plan.spacing * JITTER_SHARE_OF_A_SPACING,
            // Every cell, so the one holding the origin is never the one that was skipped.
            density = Density.uniform(),
        ),
        // **Scale alone, and the pivot is the bed.** A lifted copy would carry its own floor up clear of
        // the noise one and come out a dry lens with rock under it; pivoting on the bed instead keeps
        // every chamber's lower half buried however big it is drawn, so the ground you stand on is always
        // the same noise surface and the lake in it is always where it says.
        variation = Variation(
            yawSteps = 1,
            minScale = SMALLEST_AGAINST_ITS_KIND,
            maxScale = 1.0,
            scaleSteps = DISTINCT_SIZES,
            pivotY = plan.bedY,
        ),
        seed = CHAMBER_SEED xor salt,
    )

    /**
     * The ground inside them — **the bottom of every chamber**, one noise surface across the whole world,
     * so each vault's bed is its own and the islands standing out of the water are never twice the same.
     *
     * It cuts the lower half of each lens away, which is why a chamber's *height* is the dome over this
     * rather than the lens' own diameter. [Plan.lakeLevel] then sits a little way up the bed's swing, so
     * where it rides high the rock breaks the water and where it does not the water is simply deeper.
     */
    private fun floor(plan: Plan, salt: Long): TerrainField = NoiseHeightmap(
        seed = FLOOR_SEED xor salt,
        // Islands a couple of hundred blocks across, with shape on their flanks — the scale a city sits on.
        firstOctave = -8,
        amplitudes = listOf(1.0, 0.5, 0.25),
        scaleX = 1.0,
        scaleZ = 1.0,
        baseY = plan.bedY,
        relief = plan.bedRelief,
        flatY = plan.floorY,
    )

    /**
     * Every number the shape is built from, worked out once from the band it has to fit in.
     *
     * The band rather than the size alone, because a chamber has to *fit*: an underground eighty blocks
     * deep cannot hold a colossal vault, and the honest answer there is a smaller one rather than a vault
     * with its roof cut off square by the rock above it.
     */
    private class Plan(val floorY: Int, val roofY: Int, size: Double?) {
        val radius = betweenTheEnds(SMALLEST_RADIUS, LARGEST_RADIUS, size)
        val spacing = betweenTheEnds(CLOSEST_SPACING, WIDEST_SPACING, size)

        private val band = roofY - floorY
        private val wanted = betweenTheEnds(SHALLOWEST_CHAMBER, DEEPEST_CHAMBER, size)

        /** How far the dome stands over its bed — the headroom, and what a chamber's height means here. */
        val height = wanted
            .coerceAtMost(band - 2.0 * ROCK_AROUND_A_CHAMBER - BED_SWING * wanted)
            .coerceAtLeast(0.0)

        /** How far the bed swings either way about its own mean. */
        val bedRelief = height * BED_SWING

        /** Where that mean sits: low enough that the bed's deepest dip still leaves rock under it. */
        val bedY = floorY + ROCK_AROUND_A_CHAMBER + bedRelief.toInt()

        /**
         * And where the water stands — **a little way up the bed's swing rather than at its mean**, so
         * rather more of the floor is under water than out of it and what does stand clear reads as an
         * island in a lake instead of a lake in a plain.
         */
        val lakeLevel = bedY + (bedRelief * LAKE_ABOVE_THE_BED).toInt()

        /** Whether the band can hold one at all — see [voidBetween]. */
        val fits: Boolean get() = height >= SHALLOWEST_CHAMBER && bedY + height + ROCK_AROUND_A_CHAMBER <= roofY
    }

    private fun planFor(floorY: Int, roofY: Int, size: Double?): Plan? =
        Plan(floorY, roofY, size).takeIf { it.fits }

    /**
     * [size] read as a fraction of the way from the smallest chamber to the largest — the one place the
     * shared axis becomes this shape's own units, and null, the axis nobody spoke about, is [ORDINARY].
     */
    private fun betweenTheEnds(smallest: Double, largest: Double, size: Double?): Double {
        val fraction = size?.let(Span.NATURAL::fractionOf) ?: ORDINARY
        return smallest + fraction * (largest - smallest)
    }

    /**
     * How wide a chamber is. **The top is where a city fits**: a jigsaw structure reaches 128 blocks from
     * its start, so the island in the middle wants a couple of hundred across and the water round it more
     * again — which is what `colossal` buys and what nothing below it does.
     */
    private const val SMALLEST_RADIUS = 90.0
    private const val LARGEST_RADIUS = 380.0

    /** And how deep, floor to crown. Read with [LAKE_ABOVE_THE_BED]: most of it is air. */
    private const val SHALLOWEST_CHAMBER = 44.0
    private const val DEEPEST_CHAMBER = 150.0

    /** How far apart they lie. Wider as they grow, so a bigger chamber is not a more crowded world. */
    private const val CLOSEST_SPACING = 520.0
    private const val WIDEST_SPACING = 1400.0

    /** Where a chamber sits when nothing in the book spoke about its size. */
    private const val ORDINARY = 0.4

    /** Rock over a chamber's crown and under the lowest dip of its bed, so neither opens into the band. */
    private const val ROCK_AROUND_A_CHAMBER = 10

    /** How far the bed swings either way, against the headroom over it. */
    private const val BED_SWING = 0.22

    /**
     * Where the water stands in that swing. **Above the mean**, so most of the bed is lake and the rest is
     * island — below it and a chamber reads as a plain with puddles.
     */
    private const val LAKE_ABOVE_THE_BED = 0.4

    /** How far a chamber stands off its lattice point. Small against its radius, so the origin stays in one. */
    private const val JITTER_SHARE_OF_A_SPACING = 0.1

    /** The smallest a chamber is drawn against its kind, and how many sizes are drawn between. */
    private const val SMALLEST_AGAINST_ITS_KIND = 0.7
    private const val DISTINCT_SIZES = 3

    private const val CHAMBER_SEED = 0xC4A_9BE45L
    private const val FLOOR_SEED = 0x5E_ABED_5L
}

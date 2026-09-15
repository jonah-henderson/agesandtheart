package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.Isle
import co.voik.agesandtheart.age.aspect.Span
import co.voik.agesandtheart.worldgen.field.TerrainField
import kotlin.math.max

/**
 * Islands in an endless sea: one where the writer arrives, and the rest a voyage away.
 *
 * **Deliberately not a continent**, which is the constraint the whole preset is built around — see [Isle]
 * for why an island being an *object* with a radius rather than a threshold on a noise field is what
 * settles that. What this file adds is the part a shape cannot enforce for itself: keeping them far enough
 * apart that two never merge into one landmass. [leastApart] is that guarantee, and `IslandsCheck` asserts
 * the arithmetic rather than trusting it.
 */
object IslandsField {

    /**
     * How big an island is: at one end a day's walk across, at the other something that stops being an
     * island and starts being somewhere. `Terrain.SIZE` is the ranged parameter a sentence bends.
     *
     * **Low against their width on purpose.** These are islands rather than sea mountains: one from the
     * middle of the range stands about fifty blocks over its own beach across more than a kilometre of
     * ground, so walking it is a walk rather than a climb, and the coast stays the thing you notice about
     * it. Height and width move together, so no size draws a spike or a pancake.
     */
    private const val SMALLEST_SHORE_RADIUS = 200.0
    private const val LARGEST_SHORE_RADIUS = 1100.0
    private const val SMALLEST_PEAK_RISE = 28.0
    private const val LARGEST_PEAK_RISE = 70.0

    /** Where an island sits between those ends when nothing in the book spoke about its size. */
    private const val ORDINARY_SIZE = 0.35

    /**
     * And the ends [lone] runs between, which are wider at both because nothing has to fit around it: the
     * smallest is a rock you can see the whole of from the water, and the largest is a genuine continent
     * that the archipelago deliberately cannot draw.
     */
    private const val SMALLEST_LONE_SHORE_RADIUS = 140.0
    private const val LARGEST_LONE_SHORE_RADIUS = 2000.0

    /**
     * **Size is a footprint, and only a little of it is height** (Jonah, 2026-09-08).
     *
     * The radius runs fourteenfold across this ladder and the rise runs two and a half, which is what
     * makes a continent read as a country rather than as a mountain in the sea. It was seven, reaching a
     * hundred and fifty: a large island crowned at y=213 and stood *mostly above the lower cloud deck*,
     * so the thing you noticed about the biggest island the Art can write was that you were looking down
     * on the weather. Peaks may still break the deck — see [SMALLEST_LONE_RELIEF] — but the ground they
     * stand on does not.
     */
    private const val SMALLEST_LONE_PEAK_RISE = 22.0
    private const val LARGEST_LONE_PEAK_RISE = 55.0

    /**
     * How far a lone island's interior rolls about that crown.
     *
     * **Large against the rise on purpose**, which is what buys peaks, basins and flooded valleys instead
     * of one long swell: at the top of the ladder the interior ranges from the waterline to half again the
     * crown, so an island has somewhere to climb and somewhere to look down into. What keeps that from
     * cutting one in half is the *wavelength*, which `Isle` takes as a share of the island — a basin an
     * eighth of the island wide is a lagoon and the same depth across all of it would be a strait.
     */
    private const val SMALLEST_LONE_RELIEF = 18.0
    private const val LARGEST_LONE_RELIEF = 70.0

    /** How wide a lone island's beach is, where its radius is large enough to have the room — see [loneBeachShare]. */
    private const val LONE_BEACH_WIDTH = 90.0

    /** And how far the climb behind it runs — see [loneShoulder]. */
    private const val LONE_SHOULDER_WIDTH = 220.0

    private fun shoreRadiusAt(size: Double?): Double =
        betweenTheEnds(SMALLEST_SHORE_RADIUS, LARGEST_SHORE_RADIUS, size)

    private fun peakRiseAt(size: Double?): Double =
        betweenTheEnds(SMALLEST_PEAK_RISE, LARGEST_PEAK_RISE, size)

    /**
     * [size] read as a fraction of the way from the smallest island to the largest.
     *
     * A ranged parameter lives on the axis every span shares, so this is the one place that shared axis
     * becomes this landform's own units — and null, the axis nobody spoke about, is [ORDINARY_SIZE].
     */
    private fun betweenTheEnds(smallest: Double, largest: Double, size: Double?): Double {
        val fraction = size?.let(Span.NATURAL::fractionOf) ?: ORDINARY_SIZE
        return smallest + fraction * (largest - smallest)
    }

    fun world(size: Double? = null, salt: Long = 0L): TerrainField =
        Isle(
            floorY = WORLD_FLOOR,
            seabedY = SEABED_Y,
            shoreY = SEA_LEVEL,
            peakRise = peakRiseAt(size),
            shoreRadius = shoreRadiusAt(size),
            radiusVariation = RADIUS_VARIATION,
            spacing = spacingFor(size),
            jitter = JITTER,
            seed = ISLAND_SEED xor salt,
        )

    /**
     * **One island, and open sea however far you sail from it** — the same shape with its lattice reduced
     * to the cell the origin stands in.
     *
     * It gets a size ladder of its own, reaching well past [world]'s at both ends. What caps an island in
     * an archipelago is having to leave a voyage of open water before the next one; alone, nothing has to
     * fit around it, so the top of the range can be a landmass it takes a day to cross and the bottom a
     * rock you can see the whole of from the water.
     */
    fun lone(size: Double? = null, salt: Long = 0L): TerrainField {
        val shoreRadius = loneShoreRadiusAt(size)
        return Isle(
            floorY = WORLD_FLOOR,
            seabedY = SEABED_Y,
            shoreY = SEA_LEVEL,
            peakRise = betweenTheEnds(SMALLEST_LONE_PEAK_RISE, LARGEST_LONE_PEAK_RISE, size),
            relief = betweenTheEnds(SMALLEST_LONE_RELIEF, LARGEST_LONE_RELIEF, size),
            shoreRadius = shoreRadius,
            // The size asked for is the size drawn — see `Isle.solitary` for why a variation here would be
            // a fixed offset rather than a difference between one Age and the next.
            radiusVariation = 0.0,
            spacing = LEAST_SPACING,
            jitter = 0.0,
            seed = ISLAND_SEED xor salt,
            beachShare = loneBeachShare(shoreRadius),
            shoulder = loneShoulder(shoreRadius),
            layout = Isle.Layout.SOLITARY,
        )
    }

    /**
     * How much of a lone island's radius is beach — **a width rather than a share**, since a beach is a
     * distance you walk across and not a proportion of what is behind it.
     *
     * The two agree at an archipelago's sizes and part company at a continent's: [Isle.DEFAULT_BEACH_SHARE]
     * is a fifth, which is forty blocks of sand on the smallest island here and four hundred on the
     * largest. So the share is capped at the default and otherwise reads back off [LONE_BEACH_WIDTH],
     * which leaves the small islands exactly as they were and keeps the big ones walkable.
     */
    private fun loneBeachShare(shoreRadius: Double): Double =
        (LONE_BEACH_WIDTH / shoreRadius).coerceAtMost(Isle.DEFAULT_BEACH_SHARE)

    /**
     * And how far behind the beach the ground takes to reach its full height — **a width, for the reason
     * the beach is one**, and the reason showed up the moment the crown came down.
     *
     * A third of a two-kilometre radius is six hundred and sixty blocks to climb fifty-five, which is a
     * grade of one in twelve: not a hillside but a ramp, and `IslandsCheck` caught it by measuring a
     * hundred and seventy-seven blocks of level going before the ground rose past beach height. A stated
     * width climbs the same fifty-five over two hundred, which is a slope you notice leaving the sand —
     * and it hands the interior back the ground the ramp was eating, which is where the relief lives.
     *
     * Capped at the default, so every island small enough for a third of its radius to be the shorter
     * answer is exactly as it was.
     */
    private fun loneShoulder(shoreRadius: Double): Double =
        (LONE_SHOULDER_WIDTH / shoreRadius).coerceAtMost(Isle.DEFAULT_SHOULDER)

    private fun loneShoreRadiusAt(size: Double?): Double =
        betweenTheEnds(SMALLEST_LONE_SHORE_RADIUS, LARGEST_LONE_SHORE_RADIUS, size)

    /** The furthest a lone island of this size can reach from the origin — its coast at its widest wander. */
    fun loneReach(size: Double?): Double = loneShoreRadiusAt(size) * (1.0 + Isle.DEFAULT_COAST_ROUGHNESS)

    /** And how far its shallows carry past that, which is where open ocean actually begins. */
    fun loneShelfReach(size: Double?): Double =
        loneReach(size) + (SEA_LEVEL - SEABED_Y) / Isle.DEFAULT_SHELF_SLOPE

    /**
     * How far apart to lay islands of this size.
     *
     * The floor is what makes the sea a voyage; the multiple of the radius is what stops two of the biggest
     * ones touching. Both are needed: a fixed spacing large enough for the largest island would put the
     * smallest an absurd distance from its neighbour, and a multiple alone would put small ones in sight of
     * each other.
     */
    fun spacingFor(size: Double?): Double = max(LEAST_SPACING, shoreRadiusAt(size) * LEAST_APART)

    /**
     * The furthest an island of this size can reach from its centre — its radius at its largest draw, with
     * the coast wandering as far out as it goes. What [spacingFor] has to beat twice over.
     */
    fun widestReach(size: Double?): Double =
        shoreRadiusAt(size) * (1.0 + RADIUS_VARIATION) * (1.0 + Isle.DEFAULT_COAST_ROUGHNESS)

    /**
     * How far the *shape* reaches, shelf and all — further than [widestReach], which is about land.
     *
     * The two are different questions. Land touching is what would make a continent, and that is what the
     * spacing has to beat; shelves touching is two islands sharing shallows, which is fine and rather
     * good. This one exists so a check looking for open seabed knows where to start.
     */
    fun shelfReach(size: Double?): Double =
        widestReach(size) + (SEA_LEVEL - SEABED_Y) / Isle.DEFAULT_SHELF_SLOPE

    /**
     * The closest two neighbouring islands' centres can come, both jittered towards each other.
     *
     * `cellHash` runs −0.5..0.5, so a jitter of *j* moves a centre by half of `j * spacing` either way and
     * a pair can close by `j * spacing` in total — not twice that.
     */
    fun leastApart(size: Double?): Double = spacingFor(size) * (1.0 - JITTER)

    private const val WORLD_FLOOR = -64

    /** Deep enough that the sea between islands reads as ocean rather than as a flooded plain. */
    const val SEABED_Y = 28

    /** The convention every shape wanting a sea keeps to. */
    const val SEA_LEVEL = 63

    /** What share of its radius one island differs from the next by. */
    private const val RADIUS_VARIATION = 0.35

    /**
     * How far an island stands off its lattice point. **Small against the spacing on purpose** — jitter is
     * what stops the archipelago reading as a grid, and it is also the one thing that can bring two of them
     * close enough to merge.
     */
    private const val JITTER = 0.2

    /** The least an island may be from its neighbour, whatever its size. A voyage, not a swim. */
    private const val LEAST_SPACING = 5200.0

    /** And in radii, so the biggest ones cannot touch however the draw falls. */
    private const val LEAST_APART = 8.0

    private const val ISLAND_SEED = 0x15_1A_2DL
}

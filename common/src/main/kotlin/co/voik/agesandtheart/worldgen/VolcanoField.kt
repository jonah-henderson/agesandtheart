package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.Cone
import co.voik.agesandtheart.worldgen.field.Density
import co.voik.agesandtheart.worldgen.field.Instanced
import co.voik.agesandtheart.worldgen.field.Placement
import co.voik.agesandtheart.worldgen.field.Scatter
import co.voik.agesandtheart.worldgen.field.Subtract
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Variation

/**
 * Volcanic cones, scattered over whatever landform the Age already has (design §7.1.2).
 *
 * **A layer rather than a landform.** Volcanoes stand *on* hills or cliffs or plains rather than replacing
 * them, so this is unioned over the shape the terrain aspect built — the same way a roof is.
 *
 * **The caldera is a cut, not a shape.** A cone with the tip taken off by an inverted cone leaves a bowl in
 * the summit, which is the whole geometry: `Subtract(cone, crater)`. Building the bowl as its own positive
 * shape would have to agree with the cone's slope at every radius, and would stop agreeing the moment
 * either was resized.
 */
object VolcanoField {

    /**
     * Where the volcanoes of an Age stand.
     *
     * **The one value the terrain and the lava tubes both read**, so the cones and the vents inside them
     * agree by construction rather than by coincidence. [Placement] stores nothing per instance and draws
     * from a positional factory, so asking it twice from two places gives the same answer — which is what
     * lets a feature find a summit the noise raised.
     */
    fun sites(seed: Long): Placement = Scatter(
        cellSize = CELL,
        leastPerCell = NONE_AT_ALL,
        mostPerCell = ONE,
        density = Density(
            atOrigin = SPARSE,
            atEdge = SPARSE,
            falloffRadius = CELL,
            // Volcanic country comes in fields with quiet ground between, which is what an even sprinkle
            // over the whole world could never read as.
            patchiness = CLUSTERED,
            patchScale = REGION,
            patchSeed = seed,
        ),
    )

    /** The cones themselves, ready to be unioned over the Age's own ground. */
    fun over(seed: Long): TerrainField = Instanced(
        templates = listOf(cone()),
        placement = sites(seed),
        variation = Variation(
            yawSteps = 1,
            minScale = SMALLEST,
            maxScale = LARGEST,
            scaleSteps = SIZES,
            pivotY = BASE_Y,
        ),
        seed = seed,
        blend = SHOULDERS,
    )

    /** One mountain: a broad cone with its summit hollowed into a caldera. */
    private fun cone(): TerrainField = Subtract(
        Cone(
            baseX = 0,
            baseZ = 0,
            baseRadius = BASE_RADIUS,
            baseY = BASE_Y,
            tipY = BASE_Y + HEIGHT,
        ),
        // Inverted, so it eats downward from above the summit and leaves a bowl rather than a spike.
        Cone(
            baseX = 0,
            baseZ = 0,
            baseRadius = CALDERA_RADIUS,
            baseY = BASE_Y + HEIGHT,
            tipY = BASE_Y + HEIGHT - CALDERA_DEPTH,
        ),
    )

    /**
     * Where a cone's foot sits.
     *
     * Well below any plausible ground, because a cone is unioned *over* the Age's own terrain: starting it
     * underground is what lets it meet a hillside at whatever height that hillside happens to be, instead
     * of hanging off a cliff or floating over a valley.
     */
    private const val BASE_Y = -32

    private const val BASE_RADIUS = 56.0
    private const val HEIGHT = 96
    private const val CALDERA_RADIUS = 14.0
    private const val CALDERA_DEPTH = 18

    /** Roughly a kilometre between candidates, before the patch noise thins them further. */
    private const val CELL = 1024.0
    private const val REGION = 3000.0

    private const val NONE_AT_ALL = 0
    private const val ONE = 1

    private const val SPARSE = 0.55
    private const val CLUSTERED = 0.45

    private const val SMALLEST = 0.6
    private const val LARGEST = 1.4
    private const val SIZES = 5

    /** Enough to turn two overlapping cones into one massif rather than a crease. */
    private const val SHOULDERS = 12.0
}

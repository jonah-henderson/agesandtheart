package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.age.aspect.Rung
import co.voik.agesandtheart.worldgen.field.Density
import co.voik.agesandtheart.worldgen.field.Ellipsoid
import co.voik.agesandtheart.worldgen.field.Instanced
import co.voik.agesandtheart.worldgen.field.Intersect
import co.voik.agesandtheart.worldgen.field.Scatter
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Variation
import kotlin.math.roundToInt

/**
 * **The magma chambers, deep under everything** (design §7.1.2, Jonah 2026-09-09) — a hollow with a pool of
 * lava standing in its bottom, and the underground half of what a volcanic Age is.
 *
 * **Apart from [VolcanoField] because a chamber is its own word.** `magma_chamber` is claimed, quantified
 * and placed separately from `volcano`, and the aspect side has said so since the volcano split. Sharing a
 * file meant sharing the cones' pose constants, whose reasons are about cones — "a cone scaled down never
 * clears the ground" — while the deepest chamber is authored to clear bedrock *at its largest pose*. So a
 * cone retune silently moved a chamber's clearance, and two chambers in fourteen came out as dry rooms.
 * The pose range here is the chambers' own, set to the values they were tuned at.
 */
object MagmaChamberField {

    /**
     * The same pair of fields as a crater and for the same reason: the cavity is taken out of the rock and
     * the pool is poured into it at generation, so what a player finds is level and complete rather than
     * something that began filling as they watched.
     *
     * **One shape gives both halves of what the design asks for, and which half you get is where you dig.**
     * A chamber a carver happens to open is a lava lake in a cavern with headroom over it and a fall where
     * the cave comes in above; the same chamber sealed in rock is a mining hazard that floods the tunnel
     * the moment a pick reaches it. Nothing has to decide which — the caves and these are scattered
     * independently, so the world sorts them.
     *
     * Deep on purpose: a chamber that reached daylight would be a lava lake on a hillside, which is what
     * the small craters are for and what these are emphatically not.
     */
    fun chambers(seed: Long, amount: Double = Rung.ORDINARY): VolcanoField.Volcanoes {
        val cell = cellFor(CHAMBER_CELL, amount, CLOSEST_TOGETHER)
        val placement = Scatter(
            cellSize = cell,
            leastPerCell = NONE_AT_ALL,
            mostPerCell = ONE,
            density = Density(
                atOrigin = CHAMBERS_LIKELY,
                atEdge = CHAMBERS_LIKELY,
                falloffRadius = cell,
                patchiness = CLUSTERED,
                patchScale = REGION,
                patchSeed = seed + CHAMBER_SALT,
            ),
        )
        // Pivoted on the rock rather than on a cone's foot, which is the best one pivot can do for six
        // templates at six depths — and it is not free: scaling about y=0 moves a chamber's middle by
        // `(scale - 1) × at`, so a deep one grows *downward* as well as outward. See [CHAMBERS], where the
        // deepest is authored to clear bedrock once that drag is paid.
        val variation = Variation(
            yawSteps = TURNS,
            minScale = SMALLEST,
            maxScale = LARGEST,
            scaleSteps = SIZES,
            pivotY = ON_AXIS,
        )
        val salt = seed + CHAMBER_SALT
        return VolcanoField.Volcanoes(
            cones = Instanced(CHAMBERS.map { it.hollow() }, placement, variation, salt, Instanced.NO_BLEND),
            lakes = Instanced(CHAMBERS.map { it.pool() }, placement, variation, salt, Instanced.NO_BLEND),
        )
    }

    /**
     * One chamber, at its own depth — **the depth is per template because [Instanced] moves a copy across
     * the world and never up or down**, so a single shape would put every chamber in an Age on one level.
     */
    private data class Chamber(val at: Int, val across: Double, val tall: Double, val flooded: Double) {

        fun hollow(): TerrainField = Ellipsoid(ON_AXIS, at, ON_AXIS, across, tall)

        /** The lava in the bottom of it, level on top, with the rest left as headroom. */
        fun pool(): TerrainField = Intersect(
            listOf(hollow(), Slab(at - tall.roundToInt(), at - ((ONE_WHOLE - flooded) * tall).roundToInt())),
        )
    }

    /**
     * The chambers an Age holds, spread down the rock rather than sitting on one level.
     *
     * They differ in kind rather than by a few blocks, like the cones do: a wide shallow sump, a tall
     * narrow shaft, a great flooded hall. How much of each is lava is drawn with them, so some are a pool
     * with a cavern over it and others are nearly full to the roof.
     *
     * **The deepest of them is authored to clear bedrock at its largest pose, and that is a real
     * constraint rather than a taste** (measured 2026-09-11). [Variation] scales about y=0 and has one
     * pivot for all six, so growing a chamber also *drags it down* by `(scale - 1) × at` — the deepest was
     * `at = -50`, which at 1.3 lands its middle at −65 and its floor at −77, under an Age's bedrock at −64.
     * The hollow then came out as its own top half sitting on the world floor, and because [Chamber.pool]
     * fills from the *bottom*, the part that was lost was all of the lava. Two chambers in fourteen on seed
     * 4242 were dry rooms. `MagmaChamberCheck` holds the line; if a deeper one is ever wanted, the pose
     * range has to shrink with it.
     */
    private val CHAMBERS = listOf(
        Chamber(at = -8, across = 15.0, tall = 7.0, flooded = 0.45),
        Chamber(at = -34, across = 11.0, tall = 11.0, flooded = 0.6),
        Chamber(at = 14, across = 19.0, tall = 6.0, flooded = 0.35),
        Chamber(at = -38, across = 22.0, tall = 9.0, flooded = 0.5),
        Chamber(at = -22, across = 9.0, tall = 5.0, flooded = 0.7),
        Chamber(at = 2, across = 13.0, tall = 8.0, flooded = 0.4),
    )

    /**
     * How far apart the chambers sit, and how likely a cell is to hold one.
     *
     * Rarer than the ponds by a good margin: one of these is a find rather than scenery, and a tunnel that
     * met one every hundred blocks would be a tunnel nobody digs. About one per two hundred and sixty
     * square, which over a kilometre of country is a dozen or so.
     */
    private const val CHAMBER_CELL = 260.0
    private const val CHAMBERS_LIKELY = 0.45

    private const val CHAMBER_SALT = 0x43_4841_4D42L

    /**
     * The closest a quantifier may pack them, which is [cellFor]'s floor.
     *
     * **Inherited from the cones rather than chosen here**, and kept at their number so no Age changes: it
     * is far wider than the widest chamber at its largest pose, so it binds long before the chambers could
     * grow into one another. Worth revisiting on its own terms if the chambers are ever retuned, which is
     * the point of it having a name here at all.
     */
    private const val CLOSEST_TOGETHER = 80.0

    /** Chambers come in fields with quiet rock between, the way the cones overhead do. */
    private const val CLUSTERED = 0.4
    private const val REGION = 1800.0

    private const val NONE_AT_ALL = 0
    private const val ONE = 1

    /**
     * The chambers' own pose range, at the values they were tuned at.
     *
     * Their own rather than the cones': [CHAMBERS]' deepest template is authored to clear bedrock at
     * [LARGEST], so this number is load-bearing for the chambers in a way it is not for a mountain, and the
     * two must be free to move apart.
     */
    private const val SMALLEST = 1.0
    private const val LARGEST = 1.3
    private const val SIZES = 4
    private const val TURNS = 8

    private const val ON_AXIS = 0
    private const val ONE_WHOLE = 1.0
}

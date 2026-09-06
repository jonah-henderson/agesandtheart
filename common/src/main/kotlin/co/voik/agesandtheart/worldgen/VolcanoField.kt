package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.Cone
import co.voik.agesandtheart.worldgen.field.Density
import co.voik.agesandtheart.worldgen.field.Instanced
import co.voik.agesandtheart.worldgen.field.Intersect
import co.voik.agesandtheart.worldgen.field.Placement
import co.voik.agesandtheart.worldgen.field.Scatter
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.Subtract
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Undulated
import co.voik.agesandtheart.worldgen.field.Union
import co.voik.agesandtheart.worldgen.field.Variation
import co.voik.agesandtheart.worldgen.field.Warped
import kotlin.math.roundToInt

/**
 * Volcanic cones, scattered over whatever landform the Age already has (design §7.1.2).
 *
 * **A layer rather than a landform.** Volcanoes stand *on* hills or cliffs or plains rather than replacing
 * them, so this is unioned over the shape the terrain aspect built — the same way a roof is.
 *
 * Three things carry the shape, and each answers a different half of "this reads as geometry":
 *
 * - **The profile is two cones, not one.** A single cone has one slope, so it is a spike whatever its
 *   size. Taking the union of a steep narrow one and a shallow wide one gives a *concave* silhouette —
 *   steep at the summit, flattening into a long apron — which is what a stratovolcano's flank does and
 *   what makes the mountain read as one.
 * - **The summit is truncated and the caldera is cut into the flat.** The peak cone is clipped below its
 *   own point ([Intersect] with a [Slab]), so there is a plateau to put a crater in; without that a cone
 *   is one block wide where the crater wants to be and the cut only decapitates it.
 * - **The finished mountain is warped, then rolled, then rippled.** [Warped] moves the outline, which is
 *   the half undulation cannot reach on anything steep; the two [Undulated] layers move the surface at a
 *   long wavelength and a short one.
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
        templates = SHAPES.map { it.built(seed) },
        placement = sites(seed),
        variation = Variation(
            // Turned, because four shapes repeated unturned is still four shapes: a warped mountain has
            // a recognisable outline, and seeing the same one twice on a walk is what gives it away.
            yawSteps = TURNS,
            minScale = SMALLEST,
            maxScale = LARGEST,
            scaleSteps = SIZES,
            pivotY = BASE_Y,
        ),
        seed = seed,
        blend = SHOULDERS,
    )

    /**
     * One mountain's dials. The four below are the whole variety of volcano an Age has, so they are meant
     * to differ from each other in kind — a shield, a steep cone, a squat one, a big-crater one — rather
     * than by a few blocks.
     *
     * Heights and depths are measured from [BASE_Y], radii from the mountain's own axis.
     */
    private data class Mountain(
        /** Where the rim plateau sits: the peak cone is clipped here, so this is the summit. */
        val summitHeight: Int,
        val peakRadius: Double,
        /** Where the peak cone *would* come to a point. Above [summitHeight], or there is no plateau. */
        val peakPoint: Int,
        val calderaRadius: Double,
        val calderaFloorRadius: Double,
        val calderaDepth: Int,
        /** Separates this shape's noise from the others', so four templates are four mountains. */
        val salt: Long,
    ) {

        fun built(seed: Long): TerrainField = Warped(
            base = Undulated(
                base = Undulated(
                    base = Subtract(mountain(), caldera()),
                    seed = seed + salt + ROLL_SALT,
                    firstOctave = ROLL_OCTAVE,
                    amplitudes = ROUGHNESS,
                    scaleX = ROLL_SCALE,
                    scaleZ = ROLL_SCALE,
                    amount = ROLL_AMOUNT,
                ),
                seed = seed + salt + RIPPLE_SALT,
                firstOctave = RIPPLE_OCTAVE,
                amplitudes = ROUGHNESS,
                scaleX = RIPPLE_SCALE,
                scaleZ = RIPPLE_SCALE,
                amount = RIPPLE_AMOUNT,
            ),
            seed = seed + salt,
            firstOctave = WARP_OCTAVE,
            amplitudes = WARP_ROUGHNESS,
            scale = WARP_SCALE,
            amount = WARP_AMOUNT,
        )

        /** The steep peak, truncated flat, standing on its own shallow apron. */
        private fun mountain(): TerrainField = Union(
            listOf(
                Intersect(
                    listOf(
                        Cone(
                            baseX = ON_AXIS,
                            baseZ = ON_AXIS,
                            baseRadius = peakRadius,
                            baseY = BASE_Y,
                            tipY = BASE_Y + peakPoint,
                        ),
                        // Everything above the summit taken off, which is what leaves a plateau instead
                        // of a point. Its low end is well under the foot, so it clips nothing else.
                        Slab(lowY = BASE_Y, highY = BASE_Y + summitHeight),
                    ),
                ),
                Cone(
                    baseX = ON_AXIS,
                    baseZ = ON_AXIS,
                    baseRadius = peakRadius * APRON_SPREAD,
                    baseY = BASE_Y,
                    tipY = BASE_Y + (peakPoint * APRON_DROP).roundToInt(),
                ),
            ),
        )

        /**
         * The crater: an inverted cone with its own point clipped off, so it has a floor to stand on.
         *
         * The walls are aimed to reach full depth exactly where the floor begins, so the two agree
         * whatever any of the three dials is set to and there is never a ledge ringing the floor.
         */
        private fun caldera(): TerrainField {
            val rim = BASE_Y + summitHeight
            val wallRun = calderaDepth / (ONE_WHOLE - calderaFloorRadius / calderaRadius)
            return Intersect(
                listOf(
                    Cone(
                        baseX = ON_AXIS,
                        baseZ = ON_AXIS,
                        baseRadius = calderaRadius,
                        baseY = rim,
                        tipY = rim - wallRun.roundToInt(),
                    ),
                    Slab(lowY = rim - calderaDepth, highY = rim),
                ),
            )
        }
    }

    /**
     * A shield, a steep cone, a squat one and a wide-crater one.
     *
     * Each caldera rim is set well inside its own plateau, so the crater is a crater and not a summit
     * sliced off — the plateau radius is `peakRadius × (1 − summitHeight / peakPoint)`.
     */
    private val SHAPES = listOf(
        Mountain(
            summitHeight = 168,
            peakRadius = 300.0,
            peakPoint = 230,
            calderaRadius = 60.0,
            calderaFloorRadius = 24.0,
            calderaDepth = 30,
            salt = 0x11L,
        ),
        Mountain(
            summitHeight = 190,
            peakRadius = 250.0,
            peakPoint = 240,
            calderaRadius = 40.0,
            calderaFloorRadius = 14.0,
            calderaDepth = 26,
            salt = 0x2222L,
        ),
        Mountain(
            summitHeight = 145,
            peakRadius = 300.0,
            peakPoint = 205,
            calderaRadius = 64.0,
            calderaFloorRadius = 28.0,
            calderaDepth = 26,
            salt = 0x333333L,
        ),
        Mountain(
            summitHeight = 176,
            peakRadius = 280.0,
            peakPoint = 236,
            calderaRadius = 52.0,
            calderaFloorRadius = 20.0,
            calderaDepth = 32,
            salt = 0x44444444L,
        ),
    )

    /**
     * Where a cone's foot sits.
     *
     * Well below any plausible ground, because a cone is unioned *over* the Age's own terrain: starting it
     * underground is what lets it meet a hillside at whatever height that hillside happens to be, instead
     * of hanging off a cliff or floating over a valley.
     *
     * It is also why an apron costs so much radius. A cone based this far down shows only its last
     * hundred blocks, so a gentle *visible* slope needs a base several hundred wide — the alternative,
     * basing the apron near the surface, is a mountain that floats wherever the ground runs low.
     */
    private const val BASE_Y = -32

    /** How much wider and lower the apron cone is than the peak it stands under. */
    private const val APRON_SPREAD = 1.8
    private const val APRON_DROP = 0.70

    /**
     * How far the outline wanders, and over what wavelength.
     *
     * The big lever on whether a volcano reads as a landform, because it is the only one that reaches the
     * silhouette — see [Warped].
     *
     * **A wavelength here is `scale × 2^-firstOctave`, not `scale`**, and getting that backwards is the
     * quiet way to write a noise that does nothing: a scale of 170 at octave −6 is a swing 10,000 blocks
     * across, which over one mountain is a constant, and a constant displacement only *moves* a cone. Two
     * octaves rather than four, and weighted to the long one, because a domain warp folds over itself once
     * its gradient reaches one — and a folded lookup reads the template twice, which puts a second rim
     * inside the crater.
     */
    private const val WARP_OCTAVE = -7
    private val WARP_ROUGHNESS = listOf(1.0, 0.45)
    private const val WARP_SCALE = 1.9
    private const val WARP_AMOUNT = 30.0

    /** Whole flanks moving, then surface texture on top of them — about 120 blocks, then about 40. */
    private const val ROLL_OCTAVE = -7
    private const val RIPPLE_OCTAVE = -5
    private val ROUGHNESS = listOf(1.0, 0.9, 0.6, 0.35)
    private const val ROLL_SCALE = 0.95
    private const val ROLL_AMOUNT = 15.0
    private const val RIPPLE_SCALE = 1.3
    private const val RIPPLE_AMOUNT = 3.0

    private const val ROLL_SALT = 0x5230_4C4CL
    private const val RIPPLE_SALT = 0x5249_5050L

    /**
     * Far enough apart to be separate mountains, close enough to meet more than one on a walk.
     *
     * It is also what the per-column cost is paid in: [Instanced] scans every cell within a template's
     * reach, and the aprons reach a long way, so cells much smaller than this would have an ordinary
     * column asking after a dozen volcanoes that are not there.
     */
    private const val CELL = 580.0
    private const val REGION = 1800.0

    private const val NONE_AT_ALL = 0
    private const val ONE = 1

    /** Together these run from about one cell in five in the quiet country to every cell in a field. */
    private const val SPARSE = 0.6
    private const val CLUSTERED = 0.4

    /**
     * Never smaller than its template, because a cone scaled down never clears the ground it is laid over
     * — which is not a small volcano but an invisible one with a vent wired into a hillside. Size variety
     * comes from the four shapes instead, which is where it can be made proportionate.
     */
    private const val SMALLEST = 1.0
    private const val LARGEST = 1.3
    private const val SIZES = 4

    private const val TURNS = 8

    /** Enough to turn two overlapping aprons into one massif rather than a crease. */
    private const val SHOULDERS = 16.0

    private const val ON_AXIS = 0
    private const val ONE_WHOLE = 1.0
}

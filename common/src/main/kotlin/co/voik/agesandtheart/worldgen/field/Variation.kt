package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.util.RandomSource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * How much each copy of an [Instanced] template may differ in *pose* — how far it is turned, and how
 * big it is. This is the cheap half of instancing variety (the other half being choose-from-a-handful
 * of templates), so both dials are deliberately drawn from **finite sets**: a copy's pose is a choice,
 * never a continuous knob.
 *
 * That is what keeps the geometry honest. Sizes are a fixed set because [Instanced] pre-builds a
 * genuinely resized template for each one ([TerrainField.resized]) — a bigger pyramid gets *more
 * courses of blocks*. Scaling a copy by a continuous factor instead would mean resampling a built
 * shape at a fractional rate, which stretches its exact one-block staircase into uneven two-block
 * steps, horizontally and vertically. Turning is quantised for the same reason plus a cheaper one:
 * a small set of angles means the sines and cosines are precomputed, so the hot path does no trig.
 *
 * Because a template is authored in *absolute* Y (a [Pyramid] owns its `baseY`), resizing needs an
 * explicit [pivotY]: the plane copies stand on, which stays put while they grow or shrink.
 */
data class Variation(
    /** Allowed orientations per full turn: 1 = axis-aligned only, 4 = quarter turns, 16 ≈ free. */
    val yawSteps: Int,
    val minScale: Double,
    val maxScale: Double,
    /** How many distinct sizes to spread across [minScale]…[maxScale]; 1 = a single size. */
    val scaleSteps: Int,
    /** The Y plane resizing is anchored at — copies grow away from it, so bases stay planted. */
    val pivotY: Int,
    /** How far a copy may sit below where its template puts it, in blocks. */
    val minLift: Int = 0,
    /** How far a copy may sit above it. */
    val maxLift: Int = 0,
    /** How many distinct heights to spread across [minLift]…[maxLift]; 1 = every copy at its template's. */
    val liftSteps: Int = 1,
) {
    // Bounded because it sizes the yaw table below, and the value can arrive from a serialised tree.
    private val turns = yawSteps.coerceIn(1, MOST_YAW_STEPS)
    private val rotates = turns > 1

    // Yaw is drawn from a fixed, small set of turns, so the hot path reads cos/sin instead of computing it.
    private val cosines = DoubleArray(turns) { step -> cos(step * FULL_TURN / turns) }
    private val sines = DoubleArray(turns) { step -> sin(step * FULL_TURN / turns) }

    /**
     * The sizes copies may take, evenly spread across [minScale]…[maxScale]. A field is resized by
     * these once, up front — never per instance.
     */
    val scaleFactors: List<Double> = spreadScaleFactors()

    /**
     * The heights copies may sit at, relative to where their template puts them.
     *
     * Unlike [scaleFactors] these need no pre-built templates, because a lift is an exact integer
     * translation of the sampled column ([Spans.shifted]) rather than a resampling. So where sizes cost
     * one built field each, heights cost nothing — which is why this dial can be generous.
     */
    val liftOffsets: List<Int> = spreadLifts()

    // Whether a copy's height is ever anything but its template's, so the common case draws no
    // randomness at all and fields written before lifts existed keep the exact worlds they had.
    private val lifts = liftOffsets.size > 1 || liftOffsets.first() != 0

    /** Every size of [template] this variation can place, ready to be chosen between. */
    fun sizesOf(template: TerrainField): List<TerrainField> =
        scaleFactors.map { factor -> template.resized(factor, pivotY) }

    /** This variation with its lifts scaled by [factor], for when the whole instanced field is resized. */
    fun resized(factor: Double): Variation = copy(
        minLift = (minLift * factor).roundToInt(),
        maxLift = (maxLift * factor).roundToInt(),
    )

    /**
     * Sample an already-resized [template] for the column at ([localX], [localZ]) — an offset from the
     * instance's own origin — turned by an angle drawn from [instanceRandom]. Turning is the one part
     * of a pose that can't be pre-built, so it is done here by inverse-rotating the query column into
     * the template's frame.
     */
    fun sample(template: TerrainField, localX: Int, localZ: Int, instanceRandom: RandomSource): Spans {
        val standing = turned(template, localX, localZ, instanceRandom)
        if (!lifts) return standing
        return standing.shifted(liftOffsets[instanceRandom.nextInt(liftOffsets.size)])
    }

    private fun turned(template: TerrainField, localX: Int, localZ: Int, instanceRandom: RandomSource): Spans {
        if (!rotates) return template.columnSpans(localX, localZ)

        val step = instanceRandom.nextInt(turns)
        val cosine = cosines[step]
        val sine = sines[step]
        // Rotate the query by -yaw to find which template column this world column lands on. Rounding
        // picks the nearest one, so non-axis-aligned edges alias into a staircase — inherent to turning
        // voxel geometry, and the reason turning is the only part of a pose left as resampling.
        val templateX = (localX * cosine + localZ * sine).roundToInt()
        val templateZ = (localZ * cosine - localX * sine).roundToInt()
        return template.columnSpans(templateX, templateZ)
    }

    private fun spreadLifts(): List<Int> {
        val lowest = minOf(minLift, maxLift)
        val highest = maxOf(minLift, maxLift)
        val heights = liftSteps.coerceIn(1, MOST_LIFT_STEPS)
        if (heights == 1 || lowest == highest) return listOf(lowest)
        return (0..<heights).map { step -> lowest + (highest - lowest) * step / (heights - 1) }
    }

    private fun spreadScaleFactors(): List<Double> {
        val smallest = minScale.coerceAtLeast(SMALLEST_SCALE)
        val largest = maxScale.coerceAtLeast(smallest)
        val sizes = scaleSteps.coerceIn(1, MOST_SCALE_STEPS)
        if (sizes == 1) return listOf(smallest)
        return (0..<sizes).map { step -> smallest + (largest - smallest) * step / (sizes - 1) }
    }

    companion object {
        /** Every copy identical and axis-aligned — plain repetition, the instancing default. */
        val NONE = Variation(yawSteps = 1, minScale = 1.0, maxScale = 1.0, scaleSteps = 1, pivotY = 0)

        private const val FULL_TURN = 2.0 * PI

        // Guards on values that can arrive from a serialised tree: a size must stay positive, and
        // neither set may grow big enough to make its precomputed table a problem.
        private const val SMALLEST_SCALE = 0.01
        private const val MOST_YAW_STEPS = 256
        private const val MOST_SCALE_STEPS = 64
        private const val MOST_LIFT_STEPS = 64

        val CODEC: MapCodec<Variation> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.INT.fieldOf("yaw_steps").forGetter(Variation::yawSteps),
                Codec.DOUBLE.fieldOf("min_scale").forGetter(Variation::minScale),
                Codec.DOUBLE.fieldOf("max_scale").forGetter(Variation::maxScale),
                Codec.INT.fieldOf("scale_steps").forGetter(Variation::scaleSteps),
                Codec.INT.fieldOf("pivot_y").forGetter(Variation::pivotY),
                // Optional, so field trees serialised before lifts existed still load — and read back
                // as no lift at all, which is the world they were generated with.
                Codec.INT.optionalFieldOf("min_lift", 0).forGetter(Variation::minLift),
                Codec.INT.optionalFieldOf("max_lift", 0).forGetter(Variation::maxLift),
                Codec.INT.optionalFieldOf("lift_steps", 1).forGetter(Variation::liftSteps),
            ).apply(instance, ::Variation)
        }
    }
}

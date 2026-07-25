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
 * How much each copy of an [Instanced] template may differ in *pose* — a yaw turn about its own
 * vertical axis and a uniform size factor. This is the cheap half of instancing variety (the other
 * half being choose-from-a-handful of templates); it never reshapes a field, so nothing is built or
 * allocated per instance.
 *
 * Both are applied by **inverse-transforming the query column** into the template's own frame and
 * rescaling the spans that come back — see [sample]. Because a template is authored in absolute Y
 * (a [Pyramid] knows its own `baseY`), vertical scaling needs an explicit [pivotY]: the plane the
 * copies stand on, which stays put while they grow or shrink.
 */
data class Variation(
    /** Allowed orientations per full turn: 1 = axis-aligned only, 4 = quarter turns, 16 ≈ free. */
    val yawSteps: Int,
    val minScale: Double,
    val maxScale: Double,
    /** The Y plane that scaling is anchored at — copies grow away from it, so bases stay planted. */
    val pivotY: Int,
) {
    // Bounded because it sizes the yaw table below, and the value can arrive from a serialised tree.
    private val turns = yawSteps.coerceIn(1, MOST_YAW_STEPS)
    private val rotates = turns > 1

    // Scaling divides the query by the size factor, so a zero or negative one would send the sample
    // off to infinity. Clamp rather than throw: a bad number should make a small copy, not a broken world.
    private val smallest = minScale.coerceAtLeast(SMALLEST_SCALE)
    private val largest = maxScale.coerceAtLeast(smallest)
    private val scales = smallest != 1.0 || largest != 1.0

    /** How far scaling can push a template's footprint beyond its authored reach. */
    val reachFactor: Double = maxOf(largest, 1.0)

    // Yaw is drawn from a fixed, small set of turns, so the hot path reads cos/sin instead of computing it.
    private val cosines = DoubleArray(turns) { step -> cos(step * FULL_TURN / turns) }
    private val sines = DoubleArray(turns) { step -> sin(step * FULL_TURN / turns) }

    /**
     * Sample [template] for the column at ([localX], [localZ]) — an offset from the instance's own
     * origin — under a pose drawn from [instanceRandom]. Draw order is fixed by this [Variation]'s
     * (constant) parameters, so every column that visits the same instance derives the same pose.
     */
    fun sample(template: TerrainField, localX: Int, localZ: Int, instanceRandom: RandomSource): Spans {
        if (!rotates && !scales) return template.columnSpans(localX, localZ)

        val step = if (rotates) instanceRandom.nextInt(turns) else 0
        val scale = if (scales) smallest + instanceRandom.nextDouble() * (largest - smallest) else 1.0

        // Undo the instance's pose to find which template column this world column lands on: rotate by
        // -yaw, then divide out the size. Rounding picks the nearest template column (voxel sampling).
        val cosine = cosines[step]
        val sine = sines[step]
        val templateX = ((localX * cosine + localZ * sine) / scale).roundToInt()
        val templateZ = ((localZ * cosine - localX * sine) / scale).roundToInt()

        return template.columnSpans(templateX, templateZ).scaledVertically(scale, pivotY)
    }

    companion object {
        /** Every copy identical and axis-aligned — plain repetition, the instancing default. */
        val NONE = Variation(yawSteps = 1, minScale = 1.0, maxScale = 1.0, pivotY = 0)

        private const val FULL_TURN = 2.0 * PI
        private const val SMALLEST_SCALE = 0.01

        // Far past the point where more orientations are visible on a voxel grid.
        private const val MOST_YAW_STEPS = 256

        val CODEC: MapCodec<Variation> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.INT.fieldOf("yaw_steps").forGetter(Variation::yawSteps),
                Codec.DOUBLE.fieldOf("min_scale").forGetter(Variation::minScale),
                Codec.DOUBLE.fieldOf("max_scale").forGetter(Variation::maxScale),
                Codec.INT.fieldOf("pivot_y").forGetter(Variation::pivotY),
            ).apply(instance, ::Variation)
        }
    }
}

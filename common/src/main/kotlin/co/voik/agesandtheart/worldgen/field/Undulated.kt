package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import kotlin.math.roundToInt

/**
 * [base], moved up or down by a noise **per column** — [Raised] with a lift that wanders instead of a
 * lift that is one number.
 *
 * **This is the only thing here that can rough up a surface whatever its slope**, and that is the whole
 * reason it exists. Every other way of perturbing a shape in this toolkit works at a *level*: a hanging
 * `NoiseHeightmap` subtracted as a ceiling, or intersected as a floor, cuts or fills at the height it
 * sits at, so it bites hard where the ground is near that height and not at all where the ground has
 * climbed past it. On anything steep — a crater wall, a scarp face, a cone — that is useless: a ceiling
 * low enough to reach the foot of a wall planes off its whole crest.
 *
 * Shifting the column sidesteps the question. The surface moves *with* itself, so a slope keeps its
 * gradient and its contours wander instead: a surface falling one for one has its contours move by the
 * shift, and a nearly level one has them move by many times it. **A surface of revolution stops being
 * one**, at one noise sample per column, without anything knowing it was round.
 *
 * Two things to know before wrapping something in it:
 *
 * - **Wrap a whole coherent layer, never one member of a union.** Shifting a shape that meets another
 *   shape separates them, and what was a hillside joining a plain becomes a hillside floating over one.
 *   Wrapping the union instead moves everything in it together and no join is disturbed.
 * - **A shifted cut moves the ground it cuts.** Subtracting an undulated shape from flat rock leaves a
 *   surface following the shift, which is often the cheapest way to get the effect: undulate the hole
 *   rather than the world around it.
 *
 * The lift is exact — translating an interval is adding to both ends ([Spans.shifted]) — so nothing is
 * resampled and the base's own detail survives intact.
 */
data class Undulated(
    val base: TerrainField,
    val seed: Long,
    val firstOctave: Int,
    val amplitudes: List<Double>,
    /** Blocks per unit of noise, per axis — long wavelengths roll, short ones ripple. */
    val scaleX: Double,
    val scaleZ: Double,
    /** How far the shape may move, up or down. */
    val amount: Double,
) : TerrainField {
    override val kind = FieldKind.UNDULATED

    // Moving vertically changes nothing horizontally — the same reasoning as [Raised].
    override val horizontalReach = base.horizontalReach

    override val samplesPerColumn = base.samplesPerColumn + 1

    // Built once in the constructor and only read after, so it is safe across the chunk workers.
    private val noise = fieldNoise(seed, firstOctave, amplitudes)

    private val stretchX = scaleX.coerceAtLeast(SMALLEST_STRETCH)
    private val stretchZ = scaleZ.coerceAtLeast(SMALLEST_STRETCH)

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val standing = base.columnSpans(worldX, worldZ)
        if (standing.ranges.isEmpty()) return standing
        return standing.shifted(liftAt(worldX, worldZ))
    }

    /** How far this column's rock is moved. */
    fun liftAt(worldX: Int, worldZ: Int): Int =
        (noise.getValue(worldX / stretchX, 0.0, worldZ / stretchZ) * amount).roundToInt()

    override fun resized(factor: Double, pivotY: Int) = copy(
        base = base.resized(factor, pivotY),
        scaleX = scaleX * factor,
        scaleZ = scaleZ * factor,
        // A lift is a distance in the space the base lives in, so it scales with it.
        amount = amount * factor,
    )

    companion object {
        fun codec(self: Codec<TerrainField>): MapCodec<Undulated> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                self.fieldOf("base").forGetter(Undulated::base),
                Codec.LONG.fieldOf("seed").forGetter(Undulated::seed),
                Codec.INT.fieldOf("first_octave").forGetter(Undulated::firstOctave),
                Codec.DOUBLE.listOf().fieldOf("amplitudes").forGetter(Undulated::amplitudes),
                Codec.DOUBLE.fieldOf("scale_x").forGetter(Undulated::scaleX),
                Codec.DOUBLE.fieldOf("scale_z").forGetter(Undulated::scaleZ),
                Codec.DOUBLE.fieldOf("amount").forGetter(Undulated::amount),
            ).apply(instance, ::Undulated)
        }
    }
}

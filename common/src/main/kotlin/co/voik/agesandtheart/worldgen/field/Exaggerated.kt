package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * [base] with its height above [pivotY] stretched or squashed by a factor that wanders slowly across the world, so
 * one landform stands taller in one country and lower in the next: deeper cliffs here, shallower canyons there.
 *
 * Everything at or below [pivotY] stays where it is, so a sea keeps its shore and an underground its ceiling. The
 * factor changes over [scale] times the first octave's wavelength, far longer than anything it stretches, so a
 * slope gains almost nothing from the change in factor across it. Its [landmarks] stretch it further still in a few
 * places, eased over hundreds of blocks for the same reason.
 */
data class Exaggerated(
    val base: TerrainField,
    val pivotY: Int,
    val seed: Long,
    val firstOctave: Int,
    val amplitudes: List<Double>,
    /** Blocks per unit of noise. */
    val scale: Double,
    /** How far the factor swings either side of one. */
    val swing: Double,
    /** The highest a stretch may carry a column's top; a column already above it is only ever squashed. */
    val ceilingY: Int,
    /** The rare places stretched further still. */
    val landmarks: Landmarks = Landmarks.NONE,
) : TerrainField {
    override val kind = FieldKind.EXAGGERATED

    override val horizontalReach = base.horizontalReach

    override val samplesPerColumn = base.samplesPerColumn + 1

    // Built once in the constructor and only read after, so it is safe across the chunk workers.
    private val noise = fieldNoise(seed, firstOctave, amplitudes)

    private val stretch = scale.coerceAtLeast(SMALLEST_STRETCH)

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val standing = base.columnSpans(worldX, worldZ)
        val top = standing.highestSolidY ?: return standing
        val wanted = factorAt(worldX, worldZ)
        val raises = wanted > 1.0 && top > pivotY
        val factor = if (raises) min(wanted, max(1.0, (ceilingY - pivotY).toDouble() / (top - pivotY))) else wanted
        return standing.stretchedAbove(pivotY, factor)
    }

    /** How many times its own height the ground above [pivotY] stands in this column. */
    fun factorAt(worldX: Int, worldZ: Int): Double {
        val wander = noise.get(worldX / stretch, 0.0, worldZ / stretch).toDouble()
        return (1.0 + wander * swing).coerceAtLeast(FLATTEST) * landmarks.factorAt(worldX, worldZ)
    }

    override fun resized(factor: Double, pivotY: Int) = copy(
        base = base.resized(factor, pivotY),
        pivotY = pivotY + ((this.pivotY - pivotY) * factor).roundToInt(),
        scale = scale * factor,
        ceilingY = pivotY + ((ceilingY - pivotY) * factor).roundToInt(),
        landmarks = landmarks.resized(factor),
    )

    companion object {
        /** The lowest the factor goes however far the noise runs, so no country is planed flat. */
        private const val FLATTEST = 0.25

        fun codec(self: Codec<TerrainField>): MapCodec<Exaggerated> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                self.fieldOf("base").forGetter(Exaggerated::base),
                Codec.INT.fieldOf("pivot_y").forGetter(Exaggerated::pivotY),
                Codec.LONG.fieldOf("seed").forGetter(Exaggerated::seed),
                Codec.INT.fieldOf("first_octave").forGetter(Exaggerated::firstOctave),
                Codec.DOUBLE.listOf().fieldOf("amplitudes").forGetter(Exaggerated::amplitudes),
                Codec.DOUBLE.fieldOf("scale").forGetter(Exaggerated::scale),
                Codec.DOUBLE.fieldOf("swing").forGetter(Exaggerated::swing),
                Codec.INT.fieldOf("ceiling_y").forGetter(Exaggerated::ceilingY),
                Landmarks.CODEC.optionalFieldOf("landmarks", Landmarks.NONE).forGetter(Exaggerated::landmarks),
            ).apply(instance, ::Exaggerated)
        }
    }
}

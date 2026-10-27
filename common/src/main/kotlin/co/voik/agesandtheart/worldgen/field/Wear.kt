package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * How worn the land is from place to place: [mean] on average, swinging by [spread] of it either way over a few
 * kilometres, so one stretch is barely touched and the next deeply worn. One is the ordinary amount; never below
 * none nor over [MOST].
 *
 * Every field of the weather's wear reads the same map, so a worn place is worn in every way at once.
 */
data class Wear(
    val seed: Long,
    val mean: Double,
    val spread: Double,
    /** Blocks to a unit of the noise; a worn or unworn stretch runs about this far. */
    val stretch: Double = DEFAULT_STRETCH,
) {
    private val noise = fieldNoise(seed, OCTAVE, AMPLITUDES)

    /** The most wear anywhere, which bounds how far a field has to look. */
    val most: Double get() = (mean * (1.0 + spread)).coerceIn(0.0, MOST)

    fun at(worldX: Int, worldZ: Int): Double {
        if (spread <= 0.0) return mean.coerceIn(0.0, MOST)
        val swing = noise.get(worldX / stretch.coerceAtLeast(SMALLEST_STRETCH), 0.0, worldZ / stretch.coerceAtLeast(SMALLEST_STRETCH))
        return (mean * (1.0 + spread * swing.toDouble())).coerceIn(0.0, MOST)
    }

    fun resized(factor: Double) = copy(stretch = stretch * factor)

    companion object {
        const val MOST = 2.0

        private const val DEFAULT_STRETCH = 1200.0
        private const val OCTAVE = 0
        private val AMPLITUDES = listOf(1.0, 0.5)

        /** The ordinary amount everywhere. */
        val EVEN = Wear(seed = 0L, mean = 1.0, spread = 0.0)

        val CODEC: Codec<Wear> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.LONG.fieldOf("seed").forGetter(Wear::seed),
                Codec.DOUBLE.fieldOf("mean").forGetter(Wear::mean),
                Codec.DOUBLE.fieldOf("spread").forGetter(Wear::spread),
                Codec.DOUBLE.optionalFieldOf("stretch", DEFAULT_STRETCH).forGetter(Wear::stretch),
            ).apply(instance, ::Wear)
        }

        /** How a field carries its wear: under `wear`, and the ordinary amount everywhere when it has none. */
        val FIELD: MapCodec<Wear> = CODEC.optionalFieldOf("wear", EVEN)
    }
}

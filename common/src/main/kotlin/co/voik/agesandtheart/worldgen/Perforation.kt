package co.voik.agesandtheart.worldgen

import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import net.minecraft.world.level.levelgen.synth.SimplexNoise
import kotlin.random.Random

/**
 * Round holes through whatever is laid, `porous` at a scale of [holeSize] blocks: a smooth noise of the place, and a
 * hole wherever it stands over the threshold [porosity] asks for. The threshold is read off the noise itself, so a
 * porosity of a half leaves half the void one of one does, whatever the noise's spread.
 */
class Perforation(seed: Long, private val holeSize: Double, porosity: Double) {

    private val noise = SimplexNoise(XoroshiroRandomSource(seed))

    private val threshold: Double = run {
        val void = porosity.coerceIn(0.0, 1.0) * MOST_VOID
        val sampling = Random(SAMPLING_SEED)
        val samples = DoubleArray(SAMPLES) {
            noise.get(sampling.nextDouble() * SAMPLED_ACROSS, sampling.nextDouble() * SAMPLED_ACROSS, sampling.nextDouble() * SAMPLED_ACROSS).toDouble()
        }.sorted()
        samples[((1.0 - void) * (SAMPLES - 1)).toInt().coerceIn(0, SAMPLES - 1)]
    }

    val isNothing: Boolean = porosity <= 0.0

    fun isHoleAt(x: Int, y: Int, z: Int): Boolean =
        !isNothing && noise.get(x / holeSize, y / holeSize, z / holeSize) > threshold

    companion object {
        /** The share of what is laid a porosity of one leaves void. */
        const val MOST_VOID = 0.35

        private const val SAMPLES = 4096
        private const val SAMPLED_ACROSS = 1000.0
        private const val SAMPLING_SEED = 0x504F_524FL
    }
}

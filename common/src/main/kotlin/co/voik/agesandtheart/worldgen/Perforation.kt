package co.voik.agesandtheart.worldgen

import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import net.minecraft.world.level.levelgen.synth.SimplexNoise
import kotlin.random.Random

/**
 * Round holes through whatever is laid, `porous` at a scale of [holeSize]: a smooth noise of the place, and a hole
 * wherever it stands over the threshold [porosity] asks for. The threshold is read off the noise itself, so a porosity
 * of a half leaves half the void one of one does, whatever the noise's spread.
 *
 * A hole comes out about four fifths of [holeSize] across at the middle porosity, measured: a scale of six makes holes
 * four or five blocks wide, and of two and a half about two.
 */
class Perforation(seed: Long, private val holeSize: Double, porosity: Double) {

    private val noise = SimplexNoise(XoroshiroRandomSource(seed))

    private val threshold: Double = thresholdLeaving(porosity) { x, y, z -> noise.get(x, y, z).toDouble() }

    val isNothing: Boolean = porosity <= 0.0

    fun isHoleAt(x: Int, y: Int, z: Int): Boolean =
        !isNothing && noise.get(x / holeSize, y / holeSize, z / holeSize) > threshold

    companion object {
        /** The share of what is laid a porosity of one leaves void. */
        const val MOST_VOID = 0.35

        /**
         * The level of [noise] that leaves the void [porosity] asks for above it, read off the noise's own spread at a
         * few thousand places so it holds for any noise, octaves and all.
         */
        fun thresholdLeaving(porosity: Double, noise: (Double, Double, Double) -> Double): Double {
            val void = porosity.coerceIn(0.0, 1.0) * MOST_VOID
            val sampling = Random(SAMPLING_SEED)
            val samples = DoubleArray(SAMPLES) {
                noise(sampling.nextDouble() * SAMPLED_ACROSS, sampling.nextDouble() * SAMPLED_ACROSS, sampling.nextDouble() * SAMPLED_ACROSS)
            }.sorted()
            return samples[((1.0 - void) * (SAMPLES - 1)).toInt().coerceIn(0, SAMPLES - 1)]
        }

        private const val SAMPLES = 4096
        private const val SAMPLED_ACROSS = 1000.0
        private const val SAMPLING_SEED = 0x504F_524FL
    }
}

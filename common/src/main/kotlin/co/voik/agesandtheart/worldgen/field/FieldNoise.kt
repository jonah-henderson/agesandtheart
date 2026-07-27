package co.voik.agesandtheart.worldgen.field

import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import net.minecraft.world.level.levelgen.synth.NormalNoise

/**
 * The noise every sampling field is built on, in one place.
 *
 * [NoiseHeightmap], [Noise3D] and [WaterTable] all want the same thing — vanilla's own [NormalNoise],
 * seeded per field, from an octave and a list of amplitudes that may have arrived from a serialised
 * recipe. They each used to build it themselves, identically, and the three copies were exactly as
 * useful as one.
 */

/**
 * Vanilla's [NormalNoise], with its two hostile inputs settled first.
 *
 * Neither is trusted, because both can come out of a serialised field tree: vanilla forbids more
 * amplitudes than the first octave leaves room for, an empty list would leave the noise nothing to sum,
 * and a positive [firstOctave] would ask for a negative number of amplitudes — which `take` rejects
 * outright, failing in the constructor rather than anywhere informative.
 */
internal fun fieldNoise(seed: Long, firstOctave: Int, amplitudes: List<Double>): NormalNoise {
    val weights = amplitudes.take((-firstOctave + 1).coerceAtLeast(0)).ifEmpty { listOf(1.0) }
    return NormalNoise.create(XoroshiroRandomSource(seed), firstOctave, *weights.toDoubleArray())
}

/** A zero stretch would divide the sample coordinates to infinity, so every scale is held above this. */
internal const val SMALLEST_STRETCH = 0.01

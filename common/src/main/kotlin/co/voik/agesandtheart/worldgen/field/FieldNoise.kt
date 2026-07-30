package co.voik.agesandtheart.worldgen.field

import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import net.minecraft.world.level.levelgen.synth.NormalNoise

/**
 * The noise every sampling field is built on, in one place: vanilla's own [NormalNoise], seeded per
 * field, from an octave and amplitudes that may have arrived from a serialised recipe.
 */

/**
 * Vanilla's [NormalNoise], with its two hostile inputs settled first — neither is trusted, both being
 * able to arrive from a serialised field tree. Vanilla forbids more amplitudes than the first octave
 * leaves room for, an empty list leaves the noise nothing to sum, and a positive [firstOctave] asks for
 * a negative count, which `take` rejects somewhere uninformative.
 */
internal fun fieldNoise(seed: Long, firstOctave: Int, amplitudes: List<Double>): NormalNoise {
    val weights = amplitudes.take((-firstOctave + 1).coerceAtLeast(0)).ifEmpty { listOf(1.0) }
    return NormalNoise.create(XoroshiroRandomSource(seed), firstOctave, *weights.toDoubleArray())
}

/** A zero stretch would divide the sample coordinates to infinity, so every scale is held above this. */
internal const val SMALLEST_STRETCH = 0.01

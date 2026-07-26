package co.voik.agesandtheart.preview

import co.voik.agesandtheart.worldgen.field.NoiseCharacter
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import net.minecraft.world.level.levelgen.synth.NormalNoise

/**
 * What fraction of a volume each [NoiseCharacter] leaves solid at a given threshold — the readout to
 * consult *before* tuning a [co.voik.agesandtheart.worldgen.field.Noise3D], for the same reason the
 * terrain preview reports its weathering distribution.
 *
 * Guessing thresholds does not work, because the number means something different in every mode.
 * `NormalNoise` clusters hard around zero, so a plain threshold of 0 keeps about half the volume while
 * a *ridged* one — which peaks exactly where the raw noise is near zero — keeps almost all of it, and
 * only starts making tunnels up in the nineties. That inversion is not obvious from the formula and it
 * is expensive to find by rebuilding worlds.
 *
 * Read the table by picking the character you want and the fill you want, not by picking a threshold.
 */
fun main() {
    println("Fill fraction by threshold — ${SAMPLES / 1000}k samples per character, octaves $OCTAVES\n")
    println("            " + THRESHOLDS.joinToString("") { "%7.2f".format(it) })

    for (character in NoiseCharacter.entries) {
        val noise = NormalNoise.create(XoroshiroRandomSource(PROFILE_SEED), FIRST_OCTAVE, *AMPLITUDES)
        val above = IntArray(THRESHOLDS.size)
        forEachSample { x, y, z ->
            val shaped = character.shape(noise.getValue(x, y, z))
            THRESHOLDS.forEachIndexed { index, threshold -> if (shaped > threshold) above[index]++ }
        }
        val row = above.joinToString("") { "%6.1f%%".format(100.0 * it / SAMPLES) }
        println("  %-10s%s".format(character.getSerializedName(), row))
    }

    println(
        """
        |
        |  A cave network wants a few percent solid when read as tubes, so the rock it is cut from keeps
        |  its shape. Above roughly a fifth the tubes merge and it reads as sponge instead.
        """.trimMargin()
    )
}

/** A contiguous volume at the scale a cave field actually samples, so the figures are representative. */
private inline fun forEachSample(sample: (Double, Double, Double) -> Unit) {
    for (x in 0..<SPAN) {
        for (z in 0..<SPAN) {
            for (y in 0..<HEIGHT) {
                sample(x / STRETCH, y / STRETCH, z / STRETCH)
            }
        }
    }
}

private const val PROFILE_SEED = 0xB0FF_1CEL
private const val FIRST_OCTAVE = -5
private const val OCTAVES = 2
private val AMPLITUDES = doubleArrayOf(1.0, 0.5)

private const val SPAN = 96
private const val HEIGHT = 120
private const val SAMPLES = SPAN * SPAN * HEIGHT
private const val STRETCH = 1.4

private val THRESHOLDS = listOf(0.0, 0.2, 0.4, 0.5, 0.6, 0.7, 0.8, 0.85, 0.9, 0.95)

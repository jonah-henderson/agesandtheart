package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.NoiseCharacter
import co.voik.agesandtheart.worldgen.field.Noise3D
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Union
import co.voik.agesandtheart.worldgen.field.fieldNoise

/**
 * The `porous` underground: the rock a sponge of big round pocks, each about ten blocks across and walkable from one
 * side to the other, opening into one another where they meet and now and then joined by a crawl — a passage too
 * low to stand in. Bigger than the porous rock's vugs, which are closed pockets, and nothing like tunnels, which run.
 *
 * [scale] is [SizeScale]'s factor and grows the pocks and the crawls alike; [density] is how many times the ordinary
 * open rock, as for every underground that takes one.
 */
object PorousCaves {

    /** The pocks and crawls between [lowY] and [highY], as the space to take out of the rock. */
    fun between(lowY: Int, highY: Int, salt: Long, scale: Double = SizeScale.ORDINARY, density: Double = 1.0): TerrainField {
        val pockSeed = salt xor POCK_SALT
        val pockNoise = fieldNoise(pockSeed, POCK_OCTAVE, POCK_AMPLITUDES)
        val share = (ORDINARY_SHARE * density).coerceIn(0.0, MOST_SHARE)
        val pockThreshold = Perforation.thresholdLeaving(share / Perforation.MOST_VOID) { x, y, z -> pockNoise.get(x, y, z).toDouble() }
        val pockScale = POCK_SCALE * scale
        val pocks = Noise3D(pockSeed, POCK_OCTAVE, POCK_AMPLITUDES, pockScale, pockScale, pockScale, NoiseCharacter.PLAIN, pockThreshold, lowY, highY)
        val crawls = Noise3D(
            seed = salt xor CRAWL_SALT,
            firstOctave = CRAWL_OCTAVE,
            amplitudes = CRAWL_AMPLITUDES,
            scaleX = CRAWL_SCALE_XZ * scale,
            scaleY = CRAWL_SCALE_Y * scale,
            scaleZ = CRAWL_SCALE_XZ * scale,
            character = NoiseCharacter.RIDGED,
            threshold = CRAWL_THRESHOLD,
            lowY = lowY,
            highY = highY,
        )
        return Union(listOf(pocks, crawls))
    }

    /** The share of the band the pocks take at the ordinary density, and the most any density makes of them. */
    private const val ORDINARY_SHARE = 0.2
    private const val MOST_SHARE = 0.35

    /** The scale that makes a pock about ten blocks across. */
    private const val POCK_SCALE = 12.0
    private const val POCK_OCTAVE = 0
    private val POCK_AMPLITUDES = listOf(1.0)

    // A third of the tunnels' scale and squashed flat, and sparser: low, narrow passages rather than halls to walk.
    private const val CRAWL_SCALE_XZ = 0.5
    private const val CRAWL_SCALE_Y = 0.2
    private const val CRAWL_OCTAVE = -6
    private val CRAWL_AMPLITUDES = listOf(1.0, 0.5)
    private const val CRAWL_THRESHOLD = 0.95

    private const val POCK_SALT = 0x504F_434BL
    private const val CRAWL_SALT = 0x4352_4157L
}

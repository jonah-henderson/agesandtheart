package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.age.aspect.Span
import co.voik.agesandtheart.worldgen.field.Fissures
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The `fissured` underground: tall, narrow fissures crossing at every heading, and nowhere wide enough to
 * build on — see [Fissures] for the shape. This is where it is sized and seeded.
 *
 * **Height is a share of the band, not a length**, so a colossal fissure stands nearly the whole height of
 * whatever underground it is cut into, and an unsaid one about half. Width grows only as the square root of
 * the size factor, which holds even a colossal fissure to about thirty blocks across at its widest.
 */
object FissuresField {

    /** The fissures between [lowY] and [highY], as the space to take out of the rock. */
    fun openings(lowY: Int, highY: Int, size: Double?, salt: Long = 0L): Fissures {
        val factor = SizeScale.factorAt(size)
        val shareOfTheSizes = size?.let(Span.NATURAL::fractionOf) ?: ORDINARY_SHARE
        val band = highY - lowY + 1
        val length = (LENGTH * factor.pow(LENGTH_GROWTH)).roundToInt()
        return Fissures(
            lowY = lowY,
            highY = highY,
            spacing = (length * SPACING_PER_LENGTH).roundToInt(),
            perCell = PER_CELL,
            length = length,
            height = (band * (LEAST_SHARE_OF_BAND + (MOST_SHARE_OF_BAND - LEAST_SHARE_OF_BAND) * shareOfTheSizes))
                .roundToInt().coerceAtLeast(1),
            width = (WIDTH * sqrt(factor)).roundToInt().coerceAtLeast(NARROWEST),
            seed = (FISSURE_SEED xor salt).toInt(),
        )
    }

    // Unsaid: 120 long and 14 wide. Minuscule is 52 by 7, colossal 276 by 28.
    private const val LENGTH = 120.0
    private const val LENGTH_GROWTH = 0.6
    private const val WIDTH = 14.0
    private const val NARROWEST = 3

    // How tall the tallest fissure stands, as a share of the band: a fifth at minuscule, nearly all of it at
    // colossal. Unsaid is the halfway share.
    private const val LEAST_SHARE_OF_BAND = 0.2
    private const val MOST_SHARE_OF_BAND = 0.95
    private const val ORDINARY_SHARE = 0.5

    // Two to a cell a little shorter than the longest fissure, so they cross often without filling the rock.
    private const val SPACING_PER_LENGTH = 0.8
    private const val PER_CELL = 2

    private const val FISSURE_SEED = 0xF155_03E5L
}

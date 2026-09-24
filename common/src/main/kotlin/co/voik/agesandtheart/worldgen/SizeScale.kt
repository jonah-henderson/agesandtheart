package co.voik.agesandtheart.worldgen

import kotlin.math.pow

/**
 * The shared `size` axis read as a multiplier: a quarter at its bottom, four times at its top, and the
 * thing as tuned where nothing was said.
 *
 * Geometric, so the five size words — `minuscule`, `small`, unsaid, `large`, `colossal` — are equal steps
 * apart. What the multiplier is *of* is each reader's own business: an island's footprint, a cave's noise,
 * a cliff's drop.
 */
object SizeScale {
    const val ORDINARY = 1.0

    /** The top of the axis, and the base of the power. */
    const val COLOSSAL = 4.0

    fun factorAt(size: Double?): Double = if (size == null) ORDINARY else COLOSSAL.pow(size)
}

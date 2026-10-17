package co.voik.agesandtheart.age.aspect

import kotlin.math.ln
import kotlin.math.pow

/**
 * How often something comes, as written: a multiple of its own ordinary rate, or never stopping.
 *
 * The far end of a `frequency` dial is [isConstant], which a phenomenon answers as uninterrupted wherever it
 * can be — a fog that never lifts, a storm that never breaks — and as [often] at the most wherever it cannot.
 */
data class Recurrence(val often: Double, val isConstant: Boolean) {
    companion object {
        val ORDINARY = Recurrence(often = 1.0, isConstant = false)

        /** The multiple at either end of the dial: six times as often at the top, a sixth at the bottom. */
        const val WIDEST = 6.0

        private const val TOP_OF_THE_DIAL = 1.0

        /** A dial [steered] to a point on its axis, or [ORDINARY] where nothing was said. */
        fun of(steered: Double?): Recurrence = when {
            steered == null -> ORDINARY
            steered >= TOP_OF_THE_DIAL -> Recurrence(often = WIDEST, isConstant = true)
            else -> Recurrence(often = WIDEST.pow(steered), isConstant = false)
        }

        /** The point on the dial at which a phenomenon comes [often] times as often, short of never stopping. */
        fun steeredFor(often: Double): Double =
            (ln(often) / ln(WIDEST)).coerceIn(-TOP_OF_THE_DIAL, ALMOST_THE_TOP)

        private const val ALMOST_THE_TOP = 0.99
    }
}

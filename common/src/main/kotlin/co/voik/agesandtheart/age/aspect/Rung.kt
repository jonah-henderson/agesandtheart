package co.voik.agesandtheart.age.aspect

import kotlin.math.round

/**
 * How much of something occasional there is — the **absolute** emphasis knob, where [Share] is relative.
 *
 * Every column must have some biome, so more of one is necessarily less of another; structures are
 * occasional, so "more villages" needs no reference to anything else and nothing has to give ground.
 *
 * **A number, and the word that asked for it is a page rather than a rung of a fixed ladder.** §3.2 forbids
 * showing numbers to the *player*, not holding them: a writer lays `teeming` and the page carries the
 * amount, exactly as `arid` carries a [Span]. A named ladder was the first reading of that rule and it does
 * not scale — every word wanting a different amount needed a constant in our jar, where a pack can now ship
 * `swarming` at eight and never touch it.
 */
object Rung {
    /** However many vanilla places, untouched — and the amount that rebuilds nothing. */
    const val ORDINARY = 1.0

    /** Whether [amount] asks for anything at all; the one that does not is left strictly alone. */
    fun isOrdinary(amount: Double): Boolean = amount == ORDINARY

    /** How a claim writes it: `4.0`, and trimmed where it is whole so the common case reads plainly. */
    fun spelled(amount: Double): String =
        if (amount == amount.toLong().toDouble()) amount.toLong().toString() else amount.toString()

    /**
     * [amount] as a recipe should hold it. Two decimals is as fine as an emphasis can mean, and rounding
     * *here* rather than when spelling is what keeps a recipe reading back as exactly what was resolved —
     * a sum of tag weights is otherwise `0.2799999999999999` in every book that says it.
     */
    fun legible(amount: Double): Double = round(amount * PLACES) / PLACES

    private const val PLACES = 100.0
}

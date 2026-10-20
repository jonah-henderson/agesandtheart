package co.voik.agesandtheart.age.word

import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult

/**
 * **A bar on a tag, as a filter states it** — `">0.6"`, at least 0.6, or `"<0.2"`, at most 0.2 — held as one
 * signed number, positive for at least and negative for at most. The sign is this object's to read and
 * nobody else's; a bar is never zero, since at most nothing is an exclusion and at least nothing
 * asks for nothing.
 */
object Bars {
    /** The whole of every at-least bar — the strict reading, what a word reaches in every Age. */
    const val STRICT = 1.0

    fun isAtLeast(bar: Double): Boolean = bar > 0.0

    fun isAtMost(bar: Double): Boolean = bar < 0.0

    /** How the bar is spelled in a word file. */
    fun spell(bar: Double): String = if (isAtLeast(bar)) "$AT_LEAST$bar" else "$AT_MOST${-bar}"

    /** The bar [spelled] states, or a complaint saying how a bar is spelled. */
    fun read(spelled: String): DataResult<Double> {
        val direction = spelled.firstOrNull()
        val level = spelled.drop(1).toDoubleOrNull()
        val isSpelledRight = (direction == AT_LEAST || direction == AT_MOST) && level != null && level > 0.0 &&
            level <= 1.0
        if (!isSpelledRight) {
            return DataResult.error { "'$spelled' is no bar: write '>0.6' for at least 0.6, '<0.2' for at most 0.2" }
        }
        return DataResult.success(if (direction == AT_LEAST) level else -level)
    }

    val CODEC: Codec<Double> = Codec.STRING.comapFlatMap(::read, ::spell)

    private const val AT_LEAST = '>'
    private const val AT_MOST = '<'
}

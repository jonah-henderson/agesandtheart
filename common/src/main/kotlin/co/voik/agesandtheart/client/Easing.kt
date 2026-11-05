package co.voik.agesandtheart.client

import kotlin.math.exp

/**
 * A reading approached over about [EASE_SECONDS] of real time rather than stepped to (Jonah, walk 2026-10-06: the
 * steps were plain to see). The wind is re-read once a second and the sky light changes block by block, and either
 * one jumped the fog, the sound and the push; the cold itself still takes the server's reading as it stands.
 */
internal class Easing {
    private var shown = Float.NaN
    private var shownAt = 0L

    fun towards(target: Float): Float {
        val now = System.nanoTime()
        shown = if (shown.isNaN()) target else {
            val elapsed = (now - shownAt) / NANOS_PER_SECOND
            shown + (target - shown) * (1.0 - exp(-elapsed / EASE_SECONDS)).toFloat()
        }
        shownAt = now
        return shown
    }

    private companion object {
        /** How long the shown reading takes to cover most of the way to a new one. */
        const val EASE_SECONDS = 0.4
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}

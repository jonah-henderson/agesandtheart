package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.aspect.Recurrence
import co.voik.agesandtheart.age.aspect.Rung

/**
 * How a phenomenon befalls an Age: [amount], the rung, counting it in space where it can be counted at all
 * (`Phenomenon.countsInSpace`); [recurrence], how often it comes; [strength], how hard it is while it
 * lasts, from -1 to 1; [pace], how fast it goes, as a multiple of its own, and [writtenPace] the same as the
 * book wrote it, from -1 to 1; and [size], how big, from -1 to 1, which only a sandfall is written with.
 */
data class Befalling(
    val amount: Double,
    val recurrence: Recurrence,
    val strength: Double = ORDINARY_STRENGTH,
    val pace: Double = OWN_PACE,
    val colour: String? = null,
    /** How many ticks a turn of its colours takes, or null where they hold still. */
    val cyclePeriod: Int? = null,
    val size: Double = UNSAID,
    val writtenPace: Double = UNSAID,
) {
    companion object {
        private const val ORDINARY_STRENGTH = 0.0
        private const val OWN_PACE = 1.0
        private const val UNSAID = 0.0

        val ORDINARY = Befalling(Rung.ORDINARY, Recurrence.ORDINARY)
    }
}

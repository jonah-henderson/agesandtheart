package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.aspect.Recurrence
import co.voik.agesandtheart.age.aspect.Rung

/**
 * How a phenomenon befalls an Age: [amount], the rung, counting it in space where it can be counted at all
 * (`Phenomenon.countsInSpace`); [recurrence], how often it comes; [strength], how hard it is while it
 * lasts, from -1 to 1; and [pace], how fast it goes, as a multiple of its own.
 */
data class Befalling(
    val amount: Double,
    val recurrence: Recurrence,
    val strength: Double = ORDINARY_STRENGTH,
    val pace: Double = OWN_PACE,
) {
    companion object {
        private const val ORDINARY_STRENGTH = 0.0
        private const val OWN_PACE = 1.0

        val ORDINARY = Befalling(Rung.ORDINARY, Recurrence.ORDINARY)
    }
}

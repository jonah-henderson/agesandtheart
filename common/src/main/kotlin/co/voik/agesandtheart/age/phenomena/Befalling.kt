package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.aspect.Recurrence
import co.voik.agesandtheart.age.aspect.Rung

/**
 * How a phenomenon befalls an Age: [amount], the rung, counting it in space where it can be counted at all
 * (`Phenomenon.countsInSpace`); [recurrence], how often it comes; and [strength], how hard it is while it
 * lasts, from -1 to 1.
 */
data class Befalling(val amount: Double, val recurrence: Recurrence, val strength: Double = ORDINARY_STRENGTH) {
    companion object {
        private const val ORDINARY_STRENGTH = 0.0

        val ORDINARY = Befalling(Rung.ORDINARY, Recurrence.ORDINARY)
    }
}

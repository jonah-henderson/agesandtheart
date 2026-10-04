package co.voik.agesandtheart.desk

import kotlin.math.PI
import kotlin.math.sin

/** One of the two signals the frequency tuner mixes, each setting a step from 0 to [Tuning.STEPS] − 1. */
data class Signal(val frequency: Int, val amplitude: Int, val phase: Int) {

    /** This signal at [time], from 0 to 1 across the trace; amplitude scaled to at most 1. */
    fun at(time: Double): Double {
        val cycles = frequency + 1
        val strength = amplitude.toDouble() / (Tuning.STEPS - 1)
        val offset = phase.toDouble() / Tuning.STEPS
        return strength * sin(2 * PI * (cycles * time + offset))
    }
}

/**
 * Where a writer has set the frequency tuner's dials, and the number the mixed signal is read as (design §7.4).
 *
 * **The number is the seed the writer's next book is written at**, and the same dials always give the same
 * one, so a world found once can be found again. It is never shown: the writer sees the trace.
 */
data class Tuning(val first: Signal, val second: Signal) {

    /** The six dials in order: each signal's frequency, amplitude and phase. */
    val dials: List<Int>
        get() = listOf(first.frequency, first.amplitude, first.phase, second.frequency, second.amplitude, second.phase)

    /** The mixed signal at [time], from 0 to 1 across the trace — between −2 and 2. */
    fun mixedAt(time: Double): Double = first.at(time) + second.at(time)

    /** The dials packed into one number and scrambled, so neighbouring settings are unrelated worlds. */
    val seed: Long
        get() = scramble(dials.fold(0L) { packed, dial -> packed * STEPS + dial } xor SALT)

    fun withDial(index: Int, step: Int): Tuning = ofDials(dials.toMutableList().also { it[index] = step }) ?: this

    companion object {
        /** How many settings each dial has: sixteen to the sixth is about seventeen million worlds. */
        const val STEPS = 16
        const val DIALS = 6

        /** Where an untouched tuner's dials sit: one clean signal, the other silent. */
        val CENTRED = Tuning(Signal(frequency = 3, amplitude = 10, phase = 0), Signal(frequency = 7, amplitude = 0, phase = 0))

        /** Six steps, or null where there are not six or one is out of range. */
        fun ofDials(dials: List<Int>): Tuning? {
            if (dials.size != DIALS || dials.any { it !in 0..<STEPS }) return null
            return Tuning(Signal(dials[0], dials[1], dials[2]), Signal(dials[3], dials[4], dials[5]))
        }

        /** Keeps one tuning's seed from being a plain count of its dials. */
        private const val SALT = 0x5EED_A9E5L

        /** SplitMix64's finaliser: every bit of the input moves every bit of the output. */
        private fun scramble(value: Long): Long {
            var mixed = value + GOLDEN_GAMMA
            mixed = (mixed xor (mixed ushr 30)) * MIX_FIRST
            mixed = (mixed xor (mixed ushr 27)) * MIX_SECOND
            return mixed xor (mixed ushr 31)
        }

        private const val GOLDEN_GAMMA = -0x61c8864680b583ebL
        private const val MIX_FIRST = -0x40a7b892e31b1a47L
        private const val MIX_SECOND = -0x6b2fb644ecceee15L
    }
}

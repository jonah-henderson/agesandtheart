package co.voik.agesandtheart.desk

import io.kotest.core.spec.style.FunSpec

/** The frequency tuner's dials as a seed: the same dials always one world, neighbouring dials unrelated ones. */
class TuningCheck : FunSpec({

    test("the same dials give the same seed") {
        check(Tuning.CENTRED.seed == Tuning.ofDials(Tuning.CENTRED.dials)?.seed)
    }

    test("every single step of every dial gives a different seed") {
        val seeds = (0..<Tuning.DIALS).flatMap { dial ->
            (0..<Tuning.STEPS).map { step -> Tuning.CENTRED.withDial(dial, step).seed }
        }.toSet()
        val distinctSettings = Tuning.DIALS * (Tuning.STEPS - 1) + 1
        check(seeds.size == distinctSettings) { "${seeds.size} seeds from $distinctSettings settings" }
    }

    test("dials out of range or miscounted are refused") {
        check(Tuning.ofDials(listOf(0, 0, 0, 0, 0)) == null)
        check(Tuning.ofDials(listOf(0, 0, 0, 0, 0, Tuning.STEPS)) == null)
        check(Tuning.ofDials(listOf(0, 0, 0, 0, 0, -1)) == null)
        check(Tuning.CENTRED.withDial(0, Tuning.STEPS) == Tuning.CENTRED)
    }

    test("the mixed trace stays within the two signals' summed strength") {
        val loudest = Tuning.ofDials(listOf(15, 15, 0, 15, 15, 0)) ?: error("refused a full-strength tuning")
        val samples = (0..1000).map { loudest.mixedAt(it / 1000.0) }
        check(samples.all { it in -2.0..2.0 }) { "a sample left the trace: ${samples.filter { it !in -2.0..2.0 }}" }
    }

    /** The switch is saved with the dials, and switching off keeps where they were set. */
    test("a tuning is kept whole, on or off") {
        val on = Tuning.CENTRED.withDial(2, 9).copy(powered = true)
        check(Tuning.ofStored(on.stored) == on) { "an on tuning came back as ${Tuning.ofStored(on.stored)}" }
        val off = on.copy(powered = false)
        check(Tuning.ofStored(off.stored) == off) { "an off tuning came back as ${Tuning.ofStored(off.stored)}" }
        check(off.seed == on.seed) { "the switch moved the seed" }
        check(Tuning.ofStored(on.dials.toIntArray()) == null) { "six dials with no switch were read as a tuning" }
    }

    test("turning a dial leaves the switch where it was") {
        val on = Tuning.CENTRED.copy(powered = true)
        check(on.withDial(0, 1).powered) { "turning a dial switched the tuner off" }
        check(!Tuning.CENTRED.withDial(0, 1).powered) { "turning a dial switched the tuner on" }
    }
})

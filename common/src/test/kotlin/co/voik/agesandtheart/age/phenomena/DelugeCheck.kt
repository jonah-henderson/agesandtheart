package co.voik.agesandtheart.age.phenomena

import io.kotest.core.spec.style.FunSpec

/**
 * How far a drowning Age's sea has climbed, given how long anybody has been in it.
 *
 * **The arithmetic is all that can be checked here, and the reason is worth stating.** Everything else
 * about the deluge is near-player work: the counter advances only where `level.players()` is not empty and
 * both the catch-up and the pooling are driven by `Sampling.sweep`, which walks the chunks around players.
 * A headless server has no players, so none of it ever runs — the rise itself is a walk, not a probe.
 *
 * What a check can still hold is the shape: that it starts low, ends exactly at what was written, and stops.
 */
class DelugeCheck : FunSpec({

    test("an Age nobody has stood in opens the whole fall short of its written sea") {
        check(Deluge.shortnessAt(0) == Deluge.FALLS_BY) {
            "a deluge Age opened at something other than the full fall below its waterline"
        }
    }

    test("the sea gains a block for each span of somebody being there") {
        check(Deluge.shortnessAt(Deluge.TICKS_PER_BLOCK) == Deluge.FALLS_BY - 1) {
            "one span of presence did not buy exactly one block of sea"
        }
        check(Deluge.shortnessAt(Deluge.TICKS_PER_BLOCK * 3) == Deluge.FALLS_BY - 3) {
            "the rise is not flat — three spans bought something other than three blocks"
        }
    }

    test("it arrives at the written level and stops there") {
        val whole = Deluge.TICKS_PER_BLOCK * Deluge.FALLS_BY
        check(Deluge.shortnessAt(whole) == 0) {
            "the sea did not reach the level the recipe names"
        }
        // **The ceiling is the whole of what makes this fair** (design §5.2): a sea rising with no known
        // end is the punishment register at its purest, and the Age is meant to be *arriving* at what was
        // written rather than passing it.
        check(Deluge.shortnessAt(whole * 10) == 0) {
            "the sea climbed past the level the book named, which is the one thing it must never do"
        }
    }

    test("it resolves exactly when it arrives") {
        val whole = Deluge.TICKS_PER_BLOCK * Deluge.FALLS_BY
        check(Deluge.shortnessAt(whole - 1) > 0) { "the deluge called itself over a tick early" }
        check(Deluge.shortnessAt(whole) == 0) { "the deluge did not end when its sea got where it was going" }
    }
})

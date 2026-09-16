package co.voik.agesandtheart.age.phenomena

import io.kotest.core.spec.style.FunSpec

/**
 * How far a drowning Age's sea has climbed, given how long anybody has been in it.
 *
 * **The arithmetic is all that can be checked here, and the reason is worth stating.** Everything else
 * about the deluge is near-player work: the counter advances only where `Sampling.watchers` is not empty and
 * both the catch-up and the pooling are driven by `Sampling.sweep`, which walks the chunks around players.
 * A headless server has no players, so none of it ever runs — the rise itself is a walk, not a probe.
 *
 * What a check can still hold is the shape: that it opens at the sea the book names, climbs a block at a
 * time, and stops a fixed distance over it.
 */
class DelugeCheck : FunSpec({

    test("an Age nobody has stood in opens at the sea its book names") {
        check(Deluge.risenAt(0) == 0) {
            "a deluge Age opened at something other than its written waterline"
        }
    }

    test("the sea gains a block for each span of somebody being there") {
        check(Deluge.risenAt(Deluge.TICKS_PER_BLOCK) == 1) {
            "one span of presence did not buy exactly one block of sea"
        }
        check(Deluge.risenAt(Deluge.TICKS_PER_BLOCK * 3) == 3) {
            "the rise is not flat — three spans bought something other than three blocks"
        }
    }

    test("it climbs its whole rise and stops there") {
        val whole = Deluge.TICKS_PER_BLOCK * Deluge.RISES_BY
        check(Deluge.risenAt(whole) == Deluge.RISES_BY) {
            "the sea did not climb the whole of the rise"
        }
        // **The ceiling is the whole of what makes this fair** (design §5.2): a sea rising with no known
        // end is the punishment register at its purest, so the rise is bounded however long anybody stays.
        check(Deluge.risenAt(whole * 10) == Deluge.RISES_BY) {
            "the sea climbed past its bound, which is the one thing it must never do"
        }
    }

    test("it resolves exactly when it arrives") {
        val whole = Deluge.TICKS_PER_BLOCK * Deluge.RISES_BY
        check(Deluge.risenAt(whole - 1) < Deluge.RISES_BY) { "the deluge called itself over a tick early" }
        check(Deluge.risenAt(whole) == Deluge.RISES_BY) { "the deluge did not end when its sea got where it was going" }
    }
})

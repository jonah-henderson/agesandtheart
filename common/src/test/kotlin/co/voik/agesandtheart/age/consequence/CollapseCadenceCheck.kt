package co.voik.agesandtheart.age.consequence

import io.kotest.core.spec.style.FunSpec
import net.minecraft.util.RandomSource

/**
 * How fast a tear widens, and that it widens faster the more torn the Age is (design §5.3).
 *
 * The cadence used to be `randomTickSpeed` and so was not ours to check. Now it is a draw off the Age's own
 * tearing, and the two properties that matter are invisible from any one column: the mean **falls** as the
 * index rises, and the draw is **uneven** — a tear whose columns go at an even beat reads as machinery
 * rather than as ground giving way, which is the same argument the hadalfish's circle won on.
 *
 * **No registries, and that took one change to earn.** [Collapse.nextTurnIn] takes a tearing rather than a
 * level so it can be asked without a world; the blocks the object lays are `by lazy` so that naming it is
 * not a bootstrap, `AgeContent` being unable to initialise offline at all.
 */
class CollapseCadenceCheck : FunSpec({

    val random = RandomSource.create(0x7EA5L)
    val manyDraws = 2000

    fun meanAt(tears: Int): Double =
        (1..manyDraws).sumOf { Collapse.nextTurnIn(tears, random) }.toDouble() / manyDraws

    /**
     * **The whole point of taking the schedule off vanilla.** One `randomTickSpeed` for the server meant an
     * Age bought to the floor of coherence spread at the pace of one barely over the threshold.
     */
    test("a more torn Age takes its ground faster") {
        val means = (0..6).map(::meanAt)
        means.zipWithNext { slower, quicker ->
            check(quicker < slower) { "tearing harder did not shorten the wait: $means" }
        }
    }

    /** A floor and a ceiling, so neither end runs away however the index is bought. */
    test("the wait is bounded at both ends whatever the tearing") {
        val everyTearing = (-5..50).flatMap { tears -> (1..50).map { Collapse.nextTurnIn(tears, random) } }
        check(everyTearing.min() >= 1) { "a tear booked a turn for now or never: ${everyTearing.min()}" }
        check(everyTearing.max() <= 2000) { "a tear booked a turn beyond any use: ${everyTearing.max()}" }
    }

    /**
     * **Uneven on purpose.** A fixed delay would make a tear a metronome, and what it should look like is
     * ground going when it happens to go.
     */
    test("two columns at the same tearing do not go together") {
        val drawn = (1..200).map { Collapse.nextTurnIn(3, random) }.toSet()
        check(drawn.size > 1) { "every column booked the same turn, so a tear is a metronome" }
    }

    /**
     * A tearing of nothing is still asked — `carveInto` refuses to cut at all below the threshold, so this
     * is only ever reached by a block that already exists, and it must not divide by a zero span.
     */
    test("an untorn Age still answers with a usable wait") {
        check(Collapse.nextTurnIn(Collapse.NONE, random) > 0) { "an untorn Age booked nothing" }
    }
})

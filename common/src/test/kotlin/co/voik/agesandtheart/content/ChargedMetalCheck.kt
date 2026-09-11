package co.voik.agesandtheart.content

import io.kotest.core.spec.style.FunSpec

/**
 * **The numbers a charged machine is tuned by**, which are the half of it a walk cannot read.
 *
 * You can see whether a fence hurts; you cannot see whether it hurts *at the rate the design says*, and
 * every other figure in the material is set against one anchor: one crystal driving one copper block is
 * half a heart a second, noticeably better than a snow golem and no more. If that moves, everything a
 * player has learned about what a ratio buys moves with it silently.
 */
class ChargedMetalCheck : FunSpec({

    /** Half a heart a second: one damage, once every twenty ticks. */
    test("one crystal to one copper block is half a heart a second") {
        check(ChargedMetal.bitesEvery(ONE_TO_ONE) == A_SECOND) {
            "the anchor machine bites every ${ChargedMetal.bitesEvery(ONE_TO_ONE)} ticks, not $A_SECOND"
        }
        check(ChargedMetal.bitesFor(ONE_TO_ONE) == HALF_A_HEART) {
            "the anchor machine bites for ${ChargedMetal.bitesFor(ONE_TO_ONE)}, not $HALF_A_HEART"
        }
    }

    /**
     * **A bolt doubles a machine, and that is the whole of what the lightning buys.**
     *
     * A charged crystal is worth two ([ArcCrystalBlock]), so a machine reading its supply comes out at
     * twice the ratio with nothing about storms written into it. What that has to mean at the anchor is
     * literally double: twice the damage, twice as often.
     */
    test("a bolt doubles the anchor machine") {
        val charged = ONE_TO_ONE * ArcCrystalBlock.CHARGED_IS_WORTH
        check(ChargedMetal.bitesFor(charged) == HALF_A_HEART * 2) {
            "a charged anchor bit for ${ChargedMetal.bitesFor(charged)} rather than double"
        }
        check(ChargedMetal.bitesEvery(charged) == A_SECOND / 2) {
            "a charged anchor bit every ${ChargedMetal.bitesEvery(charged)} ticks rather than twice as often"
        }
    }

    /**
     * **Every interval it can name, it can actually keep.**
     *
     * This used to have to round: the machines turned over once every five ticks, so an interval that was
     * not a multiple of the beat landed on one far less often than it said — thirteen ticks fired every
     * sixty-five, a machine five times weaker than its own number with nothing in a screenshot to say so.
     * Applying the force every tick took the trap away rather than working around it. What is left to hold
     * is that the ramp stays inside the ends it promises, which is what makes the ceiling and the floor
     * mean something.
     */
    test("every interval the ramp names is one it can keep") {
        for (hundredths in 1..1200) {
            val force = hundredths / 100.0
            val every = ChargedMetal.bitesEvery(force)
            check(every in FASTEST..SLOWEST) { "a machine at force $force wants to bite every $every ticks" }
        }
    }

    /** More crystal over the same copper is faster and harder, all the way to the ends of both ramps. */
    test("the ramps run the way the ratio does") {
        var faster = Long.MAX_VALUE
        var harder = 0.0f
        for (hundredths in 1..1200) {
            val force = hundredths / 100.0
            check(ChargedMetal.bitesEvery(force) <= faster) { "the bite got slower at force $force" }
            check(ChargedMetal.bitesFor(force) >= harder) { "the bite got lighter at force $force" }
            faster = ChargedMetal.bitesEvery(force)
            harder = ChargedMetal.bitesFor(force)
        }
    }

    /**
     * **A mast is worth building and worth stopping.**
     *
     * Four blocks for the first rod and two for each after it, to sixteen — so seven rods is the most that
     * buys anything and an eighth is a rod somebody wasted. That last figure is the one nobody would
     * notice being off by one.
     */
    test("a mast reaches four for the first rod and two for each after, to sixteen") {
        check(Arcs.reachOfAMast(NO_RODS) == NOTHING) { "a bare mass threw ${Arcs.reachOfAMast(NO_RODS)}" }
        check(Arcs.reachOfAMast(1) == 4.0) { "one rod bought ${Arcs.reachOfAMast(1)}" }
        check(Arcs.reachOfAMast(2) == 6.0) { "two rods bought ${Arcs.reachOfAMast(2)}" }
        check(Arcs.reachOfAMast(THE_MOST_THAT_BUYS_ANYTHING) == 16.0) {
            "seven rods bought ${Arcs.reachOfAMast(THE_MOST_THAT_BUYS_ANYTHING)}, not the full sixteen"
        }
        check(Arcs.reachOfAMast(THE_MOST_THAT_BUYS_ANYTHING - 1) < 16.0) {
            "six rods already bought the full sixteen, so the seventh is decorative"
        }
        check(Arcs.reachOfAMast(THE_MOST_THAT_BUYS_ANYTHING + 1) == 16.0) {
            "an eighth rod bought more, and the maximum is not a maximum"
        }
    }

    /**
     * A run's force is crystal per metal block — **so a longer array is weaker on the same supply**, which
     * is the whole of what makes the sink building rather than fuel.
     */
    test("stretching an array without feeding it weakens it") {
        val short = Arcs.Run(along = null, blocks = List(2) { net.minecraft.core.BlockPos(it, 0, 0) }, crystal = 2)
        val long = Arcs.Run(along = null, blocks = List(8) { net.minecraft.core.BlockPos(it, 0, 0) }, crystal = 2)
        check(long.force < short.force) { "an array four times as long pulled as hard on the same crystal" }
        check(short.force == ONE_TO_ONE) { "two crystal over two blocks was not the anchor ratio" }
    }
}) {
    private companion object {
        private const val ONE_TO_ONE = 1.0
        private const val A_SECOND = 20L
        private const val HALF_A_HEART = 1.0f
        private const val NO_RODS = 0
        private const val NOTHING = 0.0

        /** Four for the first and one each after: 4 + 12 = 16, and the fourteenth rod buys nothing. */
        private const val THE_MOST_THAT_BUYS_ANYTHING = 7

        /** The ends of the bite ramp, which the ceiling and the floor of the material are read off. */
        private const val FASTEST = 10L
        private const val SLOWEST = 60L
    }
}

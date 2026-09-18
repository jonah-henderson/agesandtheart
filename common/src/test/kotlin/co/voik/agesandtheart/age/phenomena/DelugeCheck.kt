package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.DialPrice
import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Price
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.age.aspect.Rung
import io.kotest.core.spec.style.FunSpec

/**
 * How far a drowning Age's sea has climbed, given how long it has rained with anybody in it.
 *
 * **The arithmetic is all that can be checked here.** Everything else about the deluge is near-player work:
 * the counter advances only where `Sampling.watchers` is not empty and both the catch-up and the pooling are
 * driven by `Sampling.sweep`, which walks the chunks around players. A headless server has no players, so
 * none of it ever runs — the rise itself is a walk, not a probe.
 */
class DelugeCheck : FunSpec({

    val written = 63
    val top = 319

    /** A spending that bought [rate], [downpour] and [height] steps of ten on each of the deluge's dials. */
    fun bought(rate: Int = 0, downpour: Int = 0, height: Int = 0): Spending {
        val tenEach = mapOf(
            Manifestation.DELUGE to Price(Manifestation.DELUGE.dials.associateWith { DialPrice.flat(1, 10) }),
        )
        val steps = mapOf(
            Manifestation.DELUGE to mapOf(
                Manifestation.RISE_RATE to rate,
                Manifestation.DOWNPOUR to downpour,
                Manifestation.RISE_HEIGHT to height,
            ),
        )
        val ceilings = tenEach.mapValues { (_, price) -> price.dials.mapValues { it.value.most } }
        return Spending(steps, ceilings)
    }

    test("an Age nobody has stood in the rain in opens at the sea its book names") {
        check(Deluge.risenAt(0, Deluge.ORDINARY_TICKS_PER_BLOCK, Deluge.ORDINARY_RISE) == 0) {
            "a deluge Age opened at something other than its written waterline"
        }
    }

    test("the sea gains a block for each span of rain, and stops at its climb") {
        val span = 500L
        check(Deluge.risenAt(span, span, 10) == 1) { "one span did not buy exactly one block" }
        check(Deluge.risenAt(span * 3, span, 10) == 3) { "three spans bought something other than three blocks" }
        check(Deluge.risenAt(span * 10 - 1, span, 10) < 10) { "the deluge called itself over a tick early" }
        // **The ceiling is what makes this fair** (design §5.2): a sea rising with no known end is the
        // punishment register at its purest, so the rise is bounded however long anybody stays.
        check(Deluge.risenAt(span * 1000, span, 10) == 10) { "the sea climbed past its bound" }
    }

    test("an ordinary deluge climbs the ordinary rise, and a bought-out one to just under the build limit") {
        check(Deluge.climbFor(0.0, written, top) == Deluge.ORDINARY_RISE) {
            "an Age that bought no height climbed ${Deluge.climbFor(0.0, written, top)}"
        }
        val highest = written + Deluge.climbFor(1.0, written, top)
        check(highest < top && highest >= top - 4) { "a bought-out deluge stood its sea at y=$highest" }
        check(Deluge.climbFor(0.5, written, top) in (Deluge.ORDINARY_RISE + 1)..<Deluge.climbFor(1.0, written, top)) {
            "half the height dial did not land between the two ends"
        }
    }

    test("a sea written against the build limit never climbs through it") {
        check(written + Deluge.climbFor(1.0, top - 1, top) <= top) { "a sea near the top was carried past it" }
        check(Deluge.climbFor(1.0, top + 5, top) == 0) { "a sea over the top was given room to climb" }
    }

    test("every dial moves its own figure the right way, and nothing else") {
        val ordinary = Deluge.risingOf(Rung.ORDINARY, Spending.NOTHING)
        check(ordinary.ticksPerBlock == Deluge.ORDINARY_TICKS_PER_BLOCK) { "an ordinary deluge's rate moved" }
        check(ordinary.heightReach == 0.0) { "an ordinary deluge was given height" }

        val quick = Deluge.risingOf(Rung.ORDINARY, bought(rate = 10))
        check(quick.ticksPerBlock == Deluge.FASTEST_TICKS_PER_BLOCK) { "a bought-out rate is ${quick.ticksPerBlock}" }
        check(quick.rainShare == ordinary.rainShare) { "buying rate changed how often it rains" }

        val wet = Deluge.risingOf(Rung.ORDINARY, bought(downpour = 10))
        check(wet.rainShare > ordinary.rainShare && wet.rainShare < 1.0) { "a bought-out downpour asks ${wet.rainShare}" }
        check(wet.ticksPerBlock == ordinary.ticksPerBlock) { "buying downpour changed the rate" }

        val high = Deluge.risingOf(Rung.ORDINARY, bought(height = 5))
        check(high.heightReach == 0.5) { "half the height steps read as ${high.heightReach}" }
    }
})

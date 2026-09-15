package co.voik.agesandtheart.content

import io.kotest.core.spec.style.FunSpec

/**
 * The hadalfish's three speeds and the one arithmetic that couples them (design §7.1.2).
 *
 * **The orbit, the charge and the radius are one number in three places.** Holding station on a circle
 * costs a tangential speed of the turn rate times the radius, and the steering can only deliver what the
 * damping leaves of what it is aimed at — so widening the orbit without slowing the turn asks for a speed
 * the fish has not got, and it lags its own station and spirals instead of circling. None of that is
 * visible from any one constant, which is why it is checked here rather than found on a walk.
 *
 * No registries and no world: these are the numbers, and they are the whole of what is being asked.
 */
class HadalfishCheck : FunSpec({

    /**
     * **The one that breaks silently.** A wider orbit at the old turn rate needed 0.98 blocks a tick, which
     * is most of a charge — the circle would have become a chase and the telegraph would have gone with it.
     */
    test("the orbit can hold the station it is given") {
        val costs = holdingTheOrbitCosts
        val supplies = orbitCanSupply
        check(costs <= supplies) {
            "the circle turns faster than the fish can swim it: needs $costs a tick, has $supplies"
        }
    }

    /** And not so far over that the fish sits waiting on a station it reached long ago. */
    test("the orbit is not wastefully faster than the circle needs") {
        val costs = holdingTheOrbitCosts
        val supplies = orbitCanSupply
        check(supplies <= costs * COMFORTABLY) {
            "the orbit speed is far past what the circle asks: needs $costs a tick, has $supplies"
        }
    }

    /**
     * **Circle just under a wander, then a run that is nothing like either** (Jonah, 2026-09-10), where the
     * orbit used to be slower than the drift and the charge barely faster than the orbit.
     */
    test("the charge is nothing like the circle") {
        val circling = circlesAt
        val charging = chargesAt
        check(charging >= circling * UNMISTAKABLE) {
            "a charge of $charging a second does not read as one beside a circle of $circling"
        }
    }

    /** The figure the longer run through the dark was costed against. */
    test("the charge crosses the dark at the speed the reveal was priced at") {
        check(chargesAt >= WORTH_THE_DARK) {
            "a charge from beyond the fog at $chargesAt a second is a drift into view"
        }
    }
}) {
    private companion object {
        /**
         * What a steer actually delivers, as a share of the velocity it is aimed at.
         *
         * `Guardian.travelInWater` moves by the delta and *then* damps it by nine tenths, and
         * `HadalfishHunt.steer` eases [HadalfishHunt.ORBIT_EASE] of the way toward its target each tick, so
         * the steady state settles below the target.
         */
        const val STEERING_KEEPS = 0.714

        /** How fast the orbit has to travel to stay on a circle that is turning under it. */
        val holdingTheOrbitCosts = HadalfishHunt.TURN_RATE * HadalfishHunt.ORBIT_RADIUS

        /** And what the steering can actually supply against that. */
        val orbitCanSupply = HadalfishHunt.ORBIT_SPEED * STEERING_KEEPS

        /** Blocks a second, for the speeds that have to read as different things. */
        val circlesAt = orbitCanSupply * A_SECOND
        val chargesAt = HadalfishHunt.CHARGE_SPEED * A_SECOND

        const val A_SECOND = 20.0

        /** Enough headroom to turn and correct, not enough to be idling on station. */
        const val COMFORTABLY = 2.0

        /** A charge has to be several times the circle or the phases blur into one another. */
        const val UNMISTAKABLE = 3.0

        /** Blocks a second — what the walk asked for once the orbit went past the fog. */
        const val WORTH_THE_DARK = 34.0
    }
}

package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.aspect.Rung
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.util.Mth
import kotlin.math.abs

/**
 * The wind — a gale's, a blizzard's among them — never stops a sprint on the ground, on any footing, and does
 * nothing to someone sheltered. Ground motion is vanilla's: each tick the gain is added and the whole is kept at
 * friction times 0.91.
 */
@Tags(NEEDS_REGISTRIES)
class WindPushCheck : FunSpec({

    fun sprintSpeedInto(push: Double, friction: Float): Double {
        val keeps = friction * VANILLA_AIR_DRAG
        var speed = 0.0
        repeat(TICKS) { speed = (speed + Gale.sprintGainOn(friction) - push) * keeps }
        return speed
    }

    test("a sprint into the fiercest wind still gets somewhere, on ordinary ground, slime and ice") {
        for (friction in listOf(Gale.ORDINARY_FRICTION, SLIME, ICE, BLUE_ICE)) {
            val push = Gale.windPush(strength = 1.0, exposed = 1.0f, friction = friction)
            val speed = sprintSpeedInto(push, friction)
            check(speed > 0.0) { "a sprint on friction $friction into a push of $push ends at $speed" }
        }
    }

    test("the wind in the air is reckoned against ordinary ground") {
        val inTheAir = Gale.windPush(strength = 1.0, exposed = 1.0f, friction = Gale.ORDINARY_FRICTION)
        check(inTheAir < Gale.sprintGainOn(Gale.ORDINARY_FRICTION)) { "the air's push $inTheAir outruns a sprint" }
    }

    test("nothing pushes someone out of the wind, or in a storm with no strength") {
        check(Gale.windPush(strength = 1.0, exposed = 0.0f, friction = Gale.ORDINARY_FRICTION) == 0.0)
        check(Gale.windPush(strength = 0.0, exposed = 1.0f, friction = Gale.ORDINARY_FRICTION) == 0.0)
    }

    test("a blizzard's wind is furious as its storm is thick: an ordinary one a quarter as hard as one halfway to its fiercest") {
        fun pushOfAStorm(severity: Double) =
            Gale.pushStrength(DEFAULT_SCALE, Gale.Blowing(lying = 1.0, bearingDegrees = 0.0, fury = severity / Blizzard.HARDEST_EARNED))
        val ordinary = pushOfAStorm(Rung.ORDINARY)
        val halfway = pushOfAStorm((Rung.ORDINARY + Blizzard.HARDEST_EARNED) / 2)
        check(abs(ordinary / halfway - QUARTER) < TOLERANCE) { "an ordinary storm pushes at $ordinary, halfway at $halfway" }
    }

    test("a gale blowing with a blizzard makes it fiercer, never turns it, and never past whole") {
        val storm = Gale.Blowing(lying = 1.0, bearingDegrees = 90.0, fury = 0.5)
        val gale = Gale.Blowing(lying = 1.0, bearingDegrees = 200.0, fury = 1.0)
        val both = Gale.together(gale, storm)
        check(both != null && both.fury > storm.fury && both.fury <= 1.0) { "a gale and a blizzard together blew $both" }
        check(both?.bearingDegrees == storm.bearingDegrees) { "a gale turned the storm to ${both?.bearingDegrees}°" }
        check(Gale.together(null, storm) == storm && Gale.together(gale, null) == gale) { "one wind alone was changed" }
    }

    test("a blizzard's bearing turns from one day's to the next without a jump") {
        var was = Blizzard.bearingDegreesAt(SEED, 0L)
        var biggestTurn = 0.0
        for (tick in 1L..TEN_DAYS) {
            val now = Blizzard.bearingDegreesAt(SEED, tick)
            biggestTurn = maxOf(biggestTurn, abs(Mth.wrapDegrees(now - was)))
            was = now
        }
        check(biggestTurn < MOST_A_TICK_MAY_TURN) { "a blizzard turned $biggestTurn° in one tick" }
    }

    test("a strength past one is held at one") {
        val most = Gale.windPush(strength = 1.0, exposed = 1.0f, friction = ICE)
        check(Gale.windPush(strength = 3.0, exposed = 1.0f, friction = ICE) == most)
    }
}) {
    private companion object {
        const val TICKS = 200
        const val VANILLA_AIR_DRAG = 0.91f
        const val SLIME = 0.8f
        const val ICE = 0.98f
        const val BLUE_ICE = 0.989f
        const val DEFAULT_SCALE = 0.4
        const val QUARTER = 0.25
        const val TOLERANCE = 0.01
        const val SEED = 20261105L
        const val TEN_DAYS = 240_000L
        const val MOST_A_TICK_MAY_TURN = 1.0
    }
}

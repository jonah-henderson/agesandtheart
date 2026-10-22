package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.aspect.Rung
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs

/**
 * A blizzard's wind never stops a sprint on the ground, on any footing, and does nothing to someone sheltered.
 * Ground motion is vanilla's: each tick the gain is added and the whole is kept at friction times 0.91.
 */
@Tags(NEEDS_REGISTRIES)
class BlizzardWindCheck : FunSpec({

    fun sprintSpeedInto(push: Double, friction: Float): Double {
        val keeps = friction * VANILLA_AIR_DRAG
        var speed = 0.0
        repeat(TICKS) { speed = (speed + Blizzard.sprintGainOn(friction) - push) * keeps }
        return speed
    }

    test("a sprint into the fiercest wind still gets somewhere, on ordinary ground, slime and ice") {
        for (friction in listOf(Blizzard.ORDINARY_FRICTION, SLIME, ICE, BLUE_ICE)) {
            val push = Blizzard.windPush(strength = 1.0, exposed = 1.0f, friction = friction)
            val speed = sprintSpeedInto(push, friction)
            check(speed > 0.0) { "a sprint on friction $friction into a push of $push ends at $speed" }
        }
    }

    test("the wind in the air is reckoned against ordinary ground") {
        val inTheAir = Blizzard.windPush(strength = 1.0, exposed = 1.0f, friction = Blizzard.ORDINARY_FRICTION)
        check(inTheAir < Blizzard.sprintGainOn(Blizzard.ORDINARY_FRICTION)) { "the air's push $inTheAir outruns a sprint" }
    }

    test("nothing pushes someone out of the wind, or in a storm with no strength") {
        check(Blizzard.windPush(strength = 1.0, exposed = 0.0f, friction = Blizzard.ORDINARY_FRICTION) == 0.0)
        check(Blizzard.windPush(strength = 0.0, exposed = 1.0f, friction = Blizzard.ORDINARY_FRICTION) == 0.0)
    }

    test("an ordinary storm pushes a quarter as hard as one halfway to its fiercest") {
        val ordinary = Blizzard.windStrength(DEFAULT_SCALE, Rung.ORDINARY)
        val halfway = Blizzard.windStrength(DEFAULT_SCALE, (Rung.ORDINARY + Blizzard.HARDEST_EARNED) / 2)
        check(abs(ordinary / halfway - QUARTER) < TOLERANCE) { "an ordinary storm pushes at $ordinary, halfway at $halfway" }
    }

    test("a strength past one is held at one") {
        val most = Blizzard.windPush(strength = 1.0, exposed = 1.0f, friction = ICE)
        check(Blizzard.windPush(strength = 3.0, exposed = 1.0f, friction = ICE) == most)
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
    }
}

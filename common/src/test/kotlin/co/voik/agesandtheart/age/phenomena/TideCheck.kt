package co.voik.agesandtheart.age.phenomena

import co.voik.ephemeris.sky.SkyReading
import co.voik.ephemeris.sky.SkySpec
import io.kotest.core.spec.style.FunSpec

/** That a pulling moon raises the tide and lowers it, and that a sky with no pulling moon has none (design §7.1.2). */
class TideCheck : FunSpec({

    fun stageAt(dayTime: Long, sky: SkySpec = SkySpec.VANILLA) = Tide.stageFor(SkyReading.of(sky, dayTime))

    test("the moon overhead at midnight is high water") {
        check(stageAt(MIDNIGHT) == Tide.Stage.HIGH) { "midnight was ${stageAt(MIDNIGHT)}" }
    }

    test("the moon underfoot at noon is low water") {
        check(stageAt(NOON) == Tide.Stage.LOW) { "noon was ${stageAt(NOON)}" }
    }

    test("the moon on the horizon is mid tide") {
        check(stageAt(SUNRISE) == Tide.Stage.MID) { "sunrise was ${stageAt(SUNRISE)}" }
    }

    test("a moon that does not pull raises no tide") {
        val stage = Tide.stageFor(SkyReading.of(SkySpec.VANILLA, MIDNIGHT), pulls = listOf(0.0))
        check(stage == null) { "an unpulling moon raised $stage" }
    }

    test("however hard the moons pull, the tide reaches no further than the band") {
        val three = Tide.reachOf(listOf(1.0, 1.0, 1.0))
        check(three == Tide.WIDEST_REACH) { "three moons reached $three" }
        check(Tide.reachOf(listOf(0.1)) >= 1) { "a faint pull reached nothing" }
    }

    test("a sky with no moon has no tide") {
        val moonless = SkySpec.VANILLA.copy(bodies = SkySpec.VANILLA.bodies.filter { it.phase == null })
        check(stageAt(MIDNIGHT, moonless) == null) { "a moonless sky tided" }
    }
})

private const val SUNRISE = 0L
private const val NOON = 6000L
private const val MIDNIGHT = 18000L

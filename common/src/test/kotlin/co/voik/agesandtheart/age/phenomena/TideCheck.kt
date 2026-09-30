package co.voik.agesandtheart.age.phenomena

import co.voik.ephemeris.sky.SkyReading
import co.voik.ephemeris.sky.SkySpec
import io.kotest.core.spec.style.FunSpec

/** That vanilla's moon raises the tide and lowers it, and that a sky with no moon has none (design §7.1.2). */
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

    test("a sky with no moon has no tide") {
        val moonless = SkySpec.VANILLA.copy(bodies = SkySpec.VANILLA.bodies.filter { it.phase == null })
        check(stageAt(MIDNIGHT, moonless) == null) { "a moonless sky tided" }
    }
})

private const val SUNRISE = 0L
private const val NOON = 6000L
private const val MIDNIGHT = 18000L

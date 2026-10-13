package co.voik.agesandtheart.sky

import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.age.consequence.StrangeSky
import co.voik.ephemeris.sky.LevelLook
import co.voik.ephemeris.sky.SkySpec
import io.kotest.core.spec.style.FunSpec

/** What the strange tier's sky manifestations put on an Age's look, and that nothing bought puts nothing there. */
class StrangeSkyCheck : FunSpec({

    fun bought(manifestation: Manifestation, dial: String, steps: Int, most: Int) =
        Spending(mapOf(manifestation to mapOf(dial to steps)), mapOf(manifestation to mapOf(dial to most)))

    test("an Age that bought nothing has its sky left alone") {
        check(StrangeSky.lookFor(Spending.NOTHING, SEED).saysNothing) { "nothing bought still painted the sky" }
        check(StrangeSky.starsOn(SkySpec.VANILLA, Spending.NOTHING) == SkySpec.VANILLA) {
            "nothing bought still touched the stars"
        }
    }

    test("one step of pace alone brings the whole wander") {
        val wander = StrangeSky.lookFor(bought(Manifestation.SKY_DRIFT, Manifestation.DRIFT_PACE, 1, 2), SEED).wander
        check(wander != null && wander.strength > 0f && wander.colours.size > 1) { "a paced drift had no wander: $wander" }
    }

    test("stars by day are faint for one step and full for two") {
        fun byDay(steps: Int) = StrangeSky.starsOn(
            SkySpec.VANILLA,
            bought(Manifestation.DAY_STARS, Manifestation.STARS_BY_DAY, steps, 2),
        ).stars.byDay
        check(byDay(1) in 0.01f..0.99f && byDay(2) == 1f) { "day stars went ${byDay(1)} then ${byDay(2)}" }
        check(!StrangeSky.starsOn(SkySpec.VANILLA, bought(Manifestation.DAY_STARS, Manifestation.STARS_BY_DAY, 1, 2)).isOrdinary) {
            "a sky keeping its stars by day was left to vanilla, which cannot draw it"
        }
    }

    test("a monochrome Age is seen through the monochrome filter, and no other is") {
        check(StrangeSky.filtersFor(Spending.NOTHING).isEmpty()) { "nothing bought still filtered the Age" }
        val drained = StrangeSky.filtersFor(bought(Manifestation.MONOCHROME, "drained", 1, 1))
        check(drained == listOf(LevelLook.MONOCHROME)) { "a monochrome Age was filtered by $drained" }
    }

    test("slow rain falls slower and patters lower with every step") {
        check(StrangeSky.rainFor(Spending.NOTHING).isOrdinary) { "nothing bought still slowed the rain" }
        fun rain(steps: Int) = StrangeSky.rainFor(bought(Manifestation.SLOW_RAIN, Manifestation.RAIN_SLOWNESS, steps, 2))
        check(rain(2).pace < rain(1).pace && rain(1).pace < 1f) { "the rain fell at ${rain(1).pace} then ${rain(2).pace}" }
        check(rain(2).pitch < rain(1).pitch && rain(1).pitch < 1f) { "the rain sounded at ${rain(1).pitch} then ${rain(2).pitch}" }
    }

    test("a torn sky has more tears for every step, and an untorn one none") {
        check(StrangeSky.tornOver(SkySpec.VANILLA, Spending.NOTHING, SEED).tears == null) { "nothing bought still tore the sky" }
        fun tears(steps: Int) =
            StrangeSky.tornOver(SkySpec.VANILLA, bought(Manifestation.SKY_TEARS, Manifestation.TEARS_MANY, steps, 2), SEED)
                .tears?.count ?: 0
        check(tears(1) in 1..<tears(2)) { "the sky tore ${tears(1)} then ${tears(2)} times" }
    }

    test("low clouds come lower with every step") {
        fun ceiling(steps: Int) =
            StrangeSky.lookFor(bought(Manifestation.LOW_CLOUDS, Manifestation.CLOUDS_LOWNESS, steps, 2), SEED).ceiling
        val one = ceiling(1)
        val two = ceiling(2)
        check(one != null && two != null && two < one) { "low clouds went $one then $two" }
    }
}) {
    private companion object {
        const val SEED = 11L
    }
}

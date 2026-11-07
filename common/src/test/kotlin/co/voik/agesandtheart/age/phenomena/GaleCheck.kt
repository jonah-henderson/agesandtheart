package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.age.aspect.Recurrence
import io.kotest.core.spec.style.FunSpec
import net.minecraft.util.Mth
import kotlin.math.abs

/**
 * A gale comes and goes without a jump — rising and dying, and turning from one bearing to the next — blows about as
 * much of the time as it was asked to, and never leans harder than the most a push may be.
 */
class GaleCheck : FunSpec({

    val ordinary = GaleDials.NONE
    val oftenAndFierce = GaleDials(often = 1.0, push = 1.0, gusts = 0.0, force = 0.0, sudden = 0.0)

    fun blowing(dials: GaleDials, tick: Long, recurrence: Recurrence = Recurrence.ORDINARY) =
        Gale.blowingAt(SEED, tick, recurrence, dials)

    test("a gale rises and dies, and never jumps") {
        var was = blowing(oftenAndFierce, 0L)
        var biggestStep = 0.0
        for (tick in 1L..DAYS_OF_TICKS) {
            val now = blowing(oftenAndFierce, tick)
            biggestStep = maxOf(biggestStep, abs((now?.lying ?: 0.0) - (was?.lying ?: 0.0)))
            was = now
        }
        check(biggestStep < MOST_A_TICK_MAY_RISE) { "a gale rose or fell by $biggestStep in one tick" }
    }

    test("a gale turns from one bearing to the next, never jumps between them") {
        var was = blowing(oftenAndFierce, 0L)
        var biggestTurn = 0.0
        for (tick in 1L..DAYS_OF_TICKS) {
            val now = blowing(oftenAndFierce, tick)
            if (was != null && now != null) {
                biggestTurn = maxOf(biggestTurn, abs(Mth.wrapDegrees(now.bearingDegrees - was.bearingDegrees)))
            }
            was = now
        }
        check(biggestTurn < MOST_A_TICK_MAY_TURN) { "a gale turned $biggestTurn° in one tick" }
    }

    test("an ordinary gale blows about a quarter of the time, and a constant one always") {
        val windy = (0L..DAYS_OF_TICKS step SAMPLE_EVERY).count { blowing(ordinary, it) != null }
        val share = windy.toDouble() / (DAYS_OF_TICKS / SAMPLE_EVERY + 1)
        check(share in ORDINARY_SHARE_ROUGHLY) { "an ordinary gale blew $share of the time" }
        val constant = Recurrence(often = 1.0, isConstant = true)
        check((0L..DAYS_OF_TICKS step SAMPLE_EVERY).all { blowing(ordinary, it, constant)?.lying == 1.0 }) {
            "a constant gale dropped"
        }
    }

    test("fury runs from the gentlest to the whole, and the push never passes the server's dial") {
        check(Gale.furyOf(0.0) == 0.5) { "an ordinary gale's fury is ${Gale.furyOf(0.0)}" }
        check(Gale.furyOf(1.0) == 1.0) { "a gale bought in full has fury ${Gale.furyOf(1.0)}" }
        check(Gale.furyOf(-0.5) >= 0.25) { "a gentle gale's fury fell to ${Gale.furyOf(-0.5)}" }
        val fiercest = Gale.Blowing(lying = 1.0, bearingDegrees = 0.0, fury = 1.0)
        check(Gale.pushStrength(scale = 1.0, blowing = fiercest) <= 1.0) { "the fiercest gale pushes past the most" }
    }

    test("a gale makes the Age's wind as hard as it blows, rising with it") {
        val half = Gale.windOf(Gale.Blowing(lying = 0.5, bearingDegrees = 90.0, fury = 0.5))
        val whole = Gale.windOf(Gale.Blowing(lying = 1.0, bearingDegrees = 90.0, fury = 0.5))
        check(half.strength < whole.strength) { "a half-risen gale blew ${half.strength}, a whole one ${whole.strength}" }
        check(whole.strength >= 0.5f) { "a whole gale's wind ${whole.strength} is too weak to lift anything" }
        check(whole.bearingDegrees == 90.0f) { "a gale towards 90° blew the Age's wind towards ${whole.bearingDegrees}°" }
    }

    val gusty = Gusts(seed = SEED, often = 1.0, force = 1.0, sudden = 0.0)

    test("a gale with no gusts in its dials never gusts") {
        val still = Gusts.of(SEED, oftenAndFierce)
        check((0L..DAYS_OF_TICKS).none { still.at(it) != null }) { "a gale with no gusts gusted" }
    }

    test("a written gale gusts at the mild end: now and then, gently, with the most warning") {
        val written = Gusts.of(SEED, GaleDials.of(Spending(emptyMap())))
        check(written.blowsAny) { "a written gale never gusts" }
        check(written.secondsBetween in WRITTEN_GUSTS_SECONDS_APART) { "a written gale gusts every ${written.secondsBetween} s" }
        check(written.strength == 1.0) { "a written gale's gusts are ${written.strength}× the dial" }
        check(written.warningTicks == 60) { "a written gale's gusts warn ${written.warningTicks} ticks" }
        val gentle = Gusts.of(SEED, GaleDials.of(Spending(emptyMap()), written = -1.0))
        check(gentle.strength == 1.0) { "a gentle gale's gusts are ${gentle.strength}× the dial" }
    }

    test("a gust is seen and heard coming, swells smoothly, and is gone without a jump") {
        var was: Gust? = null
        var biggestStep = 0.0
        var hitUnwarned = 0
        for (tick in 0L..DAYS_OF_TICKS) {
            val now = gusty.at(tick)
            biggestStep = maxOf(biggestStep, abs((now?.presence ?: 0.0) - (was?.presence ?: 0.0)))
            val isAFreshGust = now != null && now.slot != was?.slot
            if (isAFreshGust && now.gusting > 0.0) hitUnwarned++
            was = now
        }
        check(hitUnwarned == 0) { "$hitUnwarned gusts hit with no warning" }
        check(biggestStep < MOST_A_GUST_MAY_SWELL) { "a gust's presence moved $biggestStep in one tick" }
    }

    test("the most sudden gust still gives half a second of warning, and the least three") {
        check(gusty.copy(sudden = 1.0).warningTicks == 10) { "the most sudden warns ${gusty.copy(sudden = 1.0).warningTicks} ticks" }
        check(gusty.warningTicks == 60) { "the least sudden warns ${gusty.warningTicks} ticks" }
    }

    test("gusts come about as often as their dial says") {
        val slots = DAYS_OF_TICKS / Gusts.SLOT_TICKS
        val gusted = (0L..<slots).count { slot ->
            (0L..<Gusts.SLOT_TICKS).any { gusty.at(slot * Gusts.SLOT_TICKS + it) != null }
        }
        val share = gusted.toDouble() / slots
        check(share in GUSTY_SHARE_ROUGHLY) { "gusts came in $share of slots" }
    }

    test("a gust shoves harder than the gale leans, and never past the most a push may be") {
        val ordinaryGale = Gale.Blowing(lying = 1.0, bearingDegrees = 0.0, fury = 0.5)
        val atItsHeight = Gust(slot = 0L, warned = 1.0, gusting = 1.0, presence = 1.0, veerDegrees = 0.0)
        val steady = Gale.pushStrength(DEFAULT_DIAL, ordinaryGale)
        val gentlest = Gale.pushStrength(DEFAULT_DIAL, ordinaryGale, gusty.copy(force = 0.0), atItsHeight)
        val fiercest = Gale.pushStrength(DEFAULT_DIAL, ordinaryGale, gusty, atItsHeight)
        check(gentlest >= 3.0 * steady) { "the gentlest gust pushes $gentlest against a lean of $steady" }
        check(fiercest <= 1.0) { "the fiercest gust pushes $fiercest" }
        val warning = atItsHeight.copy(warned = 0.5, gusting = 0.0)
        check(Gale.pushStrength(DEFAULT_DIAL, ordinaryGale, gusty, warning) == steady) { "a gust pushed before it hit" }
    }
}) {
    companion object {
        private const val SEED = 20261104L
        private const val DAYS_OF_TICKS = 24000L * 10
        private const val SAMPLE_EVERY = 200L
        private const val MOST_A_TICK_MAY_RISE = 0.01
        private const val MOST_A_TICK_MAY_TURN = 1.0
        private val ORDINARY_SHARE_ROUGHLY = 0.15..0.4
        private val GUSTY_SHARE_ROUGHLY = 0.7..0.9
        private const val MOST_A_GUST_MAY_SWELL = 0.5
        private const val DEFAULT_DIAL = 0.4
        private val WRITTEN_GUSTS_SECONDS_APART = 60.0..120.0
    }
}

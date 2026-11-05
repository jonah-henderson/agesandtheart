package co.voik.agesandtheart.age.phenomena

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
}) {
    companion object {
        private const val SEED = 20261104L
        private const val DAYS_OF_TICKS = 24000L * 10
        private const val SAMPLE_EVERY = 200L
        private const val MOST_A_TICK_MAY_RISE = 0.01
        private const val MOST_A_TICK_MAY_TURN = 1.0
        private val ORDINARY_SHARE_ROUGHLY = 0.15..0.4
    }
}

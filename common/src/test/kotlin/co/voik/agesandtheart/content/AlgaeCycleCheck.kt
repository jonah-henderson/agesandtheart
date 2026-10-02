package co.voik.agesandtheart.content

import io.kotest.core.spec.style.FunSpec
import net.minecraft.util.RandomSource

/**
 * **The algae keeps the hour** (design §7.6) — the cycle that gave a people with no sky a day.
 *
 * Pure arithmetic, so it needs no registries and belongs in the suite everyone runs. What it is really
 * guarding is the **phase**, which is the easy thing to get backwards and the hard thing to notice: an
 * inverted cycle looks perfectly plausible in a sealed Age, where there is no sun to disagree with it, and
 * would only ever be caught by somebody who thought to check a clock.
 */
class AlgaeCycleCheck : FunSpec({

    test("it burns by day and is out by night") {
        check(AlgaeBlock.isLitAtHour(NOON)) { "it was out at noon" }
        check(!AlgaeBlock.isLitAtHour(MIDNIGHT)) { "it was burning at midnight" }
    }

    /**
     * **The whole night, not merely midnight.** A curve stood here once and was only truly out at the one
     * instant, so every mat that had not ticked since the evening still carried a rung and the lake came
     * out speckled with lights in the dark.
     */
    test("it is out for the whole night") {
        val night = (SUNSET..<A_DAY step A_MOMENT).filter(AlgaeBlock::isLitAtHour)
        check(night.isEmpty()) { "it was still burning at ${night.size} moments after sunset: ${night.take(5)}" }
    }

    test("it is burning for the whole day") {
        val day = (0..<SUNSET step A_MOMENT).filterNot(AlgaeBlock::isLitAtHour)
        check(day.isEmpty()) { "it was out at ${day.size} moments before sunset: ${day.take(5)}" }
    }

    /** A turn ripples outward: the next mat over within half a second, and one farther off never sooner. */
    test("a ripple reaches farther mats later") {
        val random = RandomSource.create(RIPPLE_SEED)
        val nextDoor = (1..RIPPLES_TRIED).map { AlgaeBlock.rippleDelay(1.0, random) }
        val threeAway = (1..RIPPLES_TRIED).map { AlgaeBlock.rippleDelay(3.0, random) }
        check(nextDoor.all { it in 1..HALF_A_SECOND }) { "the next mat over was reached at ${nextDoor.min()}..${nextDoor.max()}" }
        check(threeAway.min() >= nextDoor.max()) { "three blocks off was reached before the next mat over" }
    }

    /** The clock is absolute rather than wrapped, so a world a hundred days old still reads its own hour. */
    test("it reads the same hour on any day") {
        for (day in listOf(0L, 1L, 99L, 100_000L)) {
            check(AlgaeBlock.isLitAtHour(day * A_DAY + NOON)) { "noon on day $day was out" }
            check(!AlgaeBlock.isLitAtHour(day * A_DAY + MIDNIGHT)) { "midnight on day $day was burning" }
        }
    }
}) {
    private companion object {
        const val A_DAY = 24_000L
        const val NOON = 6_000L
        const val SUNSET = 12_000L
        const val MIDNIGHT = 18_000L

        /** Fine enough to catch a boundary put a few ticks wrong, which is the only way this can be wrong. */
        const val A_MOMENT = 20L

        const val RIPPLE_SEED = 7L
        const val RIPPLES_TRIED = 200
        const val HALF_A_SECOND = 10
    }
}

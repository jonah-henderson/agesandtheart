package co.voik.agesandtheart.content

import io.kotest.core.spec.style.FunSpec

/**
 * **The algae keeps the hour** (design §7.6) — the cycle that gave a people with no sky a day.
 *
 * Pure arithmetic, so it needs no registries and belongs in the suite everyone runs. What it is really
 * guarding is the **phase**, which is the easy thing to get backwards and the hard thing to notice: an
 * inverted cycle looks perfectly plausible in a sealed Age, where there is no sun to disagree with it, and
 * would only ever be caught by somebody who thought to check a clock.
 */
class AlgaeCycleCheck : FunSpec({

    test("it is brightest at noon and out at midnight") {
        check(AlgaeBlock.glowAtHour(NOON) == AlgaeBlock.BRIGHTEST) {
            "at noon it stands at ${AlgaeBlock.glowAtHour(NOON)} rather than ${AlgaeBlock.BRIGHTEST}"
        }
        check(AlgaeBlock.glowAtHour(MIDNIGHT) == 0) {
            "at midnight it stands at ${AlgaeBlock.glowAtHour(MIDNIGHT)} rather than out"
        }
    }

    /** And it climbs through the morning and falls through the evening, rather than jumping at dawn. */
    test("it rises and falls rather than switching") {
        val morning = (MIDNIGHT..MIDNIGHT + HALF_A_DAY step A_MOMENT).map(AlgaeBlock::glowAtHour)
        check(morning == morning.sorted()) { "the morning did not climb: $morning" }
        val evening = (NOON..NOON + HALF_A_DAY step A_MOMENT).map(AlgaeBlock::glowAtHour)
        check(evening == evening.sortedDescending()) { "the evening did not fall: $evening" }
    }

    /**
     * **Every rung is reached**, or the fade steps through states nobody ever sees and the light jumps
     * where the state list says it should not.
     */
    test("the whole ladder is used across a day") {
        val throughTheDay = (0..<A_DAY step A_MOMENT).map(AlgaeBlock::glowAtHour).toSet()
        val ladder = (0..AlgaeBlock.BRIGHTEST).toSet()
        check(throughTheDay == ladder) { "a day reaches $throughTheDay of $ladder" }
    }

    /** The clock is absolute rather than wrapped, so a world a hundred days old still reads its own hour. */
    test("it reads the same hour on any day") {
        for (day in listOf(0L, 1L, 99L, 100_000L)) {
            val later = day * A_DAY + NOON
            check(AlgaeBlock.glowAtHour(later) == AlgaeBlock.BRIGHTEST) {
                "noon on day $day stands at ${AlgaeBlock.glowAtHour(later)}"
            }
        }
    }
}) {
    private companion object {
        const val A_DAY = 24_000L
        const val HALF_A_DAY = 12_000L
        const val NOON = 6_000L
        const val MIDNIGHT = 18_000L

        /**
         * Fine enough that every one of the fifteen rungs is sampled. The curve moves fastest at dawn and
         * dusk, where a coarse walk skips levels — and the ladder check would then be failing on how it
         * was sampled rather than on the cycle.
         */
        const val A_MOMENT = 20L
    }
}

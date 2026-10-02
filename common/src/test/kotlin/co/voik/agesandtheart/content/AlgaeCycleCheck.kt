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

    /**
     * A chunk come back after the hour turned: its stale mats have not turned at the moment the lake began
     * to, and have all turned by midnight — or by midday, the other way round.
     */
    test("a stale mat has turned by nothing at sunset and certainly by midnight") {
        check(AlgaeBlock.chanceOfHavingTurned(SUNSET) == 0.0) { "a mat had turned at the moment of sunset" }
        check(AlgaeBlock.chanceOfHavingTurned(MIDNIGHT) == 1.0) { "a mat had not certainly turned by midnight" }
        check(AlgaeBlock.chanceOfHavingTurned(MIDNIGHT + A_MOMENT * 10) == 1.0) { "past midnight, a mat had not turned" }
        check(AlgaeBlock.chanceOfHavingTurned(0L) == 0.0) { "a mat had turned at the moment of sunrise" }
        check(AlgaeBlock.chanceOfHavingTurned(NOON) == 1.0) { "a mat had not certainly turned by midday" }
    }

    test("the chance rises steadily between") {
        val evening = (SUNSET..MIDNIGHT step A_MOMENT).map(AlgaeBlock::chanceOfHavingTurned)
        val fallsBack = evening.zipWithNext().filter { (before, after) -> after < before }
        check(fallsBack.isEmpty()) { "the evening's chance fell back at ${fallsBack.size} moments" }
        check(AlgaeBlock.chanceOfHavingTurned(SUNSET + (MIDNIGHT - SUNSET) / 2) == 0.5) { "halfway to midnight was not even" }
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
    }
}

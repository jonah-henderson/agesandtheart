package co.voik.agesandtheart.age.consequence

import co.voik.agesandtheart.age.AgeRecipe
import io.kotest.core.spec.style.FunSpec

/** A crystal viewer's preview is not yet written, so nothing counted from its clock has begun. */
class PreviewClockCheck : FunSpec({

    test("a preview has stood no days at any game time") {
        val preview = Consequence(
            woundsPerChunk = 1.0,
            woundsPerDay = 1.0,
            collapseTears = 0,
            writtenAt = AgeRecipe.NOT_YET_WRITTEN,
        )
        val gameTimes = listOf(0L, 24_000L, 1_000_000_000L, Long.MAX_VALUE - 1)
        for (now in gameTimes) {
            val days = preview.daysBy(now)
            check(days == 0L) { "a preview read as $days days old at game time $now" }
        }
    }
})

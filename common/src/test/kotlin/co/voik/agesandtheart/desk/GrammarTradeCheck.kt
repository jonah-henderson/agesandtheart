package co.voik.agesandtheart.desk

import co.voik.agesandtheart.desk.StudyTier.COMMON
import co.voik.agesandtheart.desk.StudyTier.RARE
import co.voik.agesandtheart.desk.StudyTier.UNCOMMON
import io.kotest.core.spec.style.FunSpec

/** What a full grammar guide's trade asks of a notebook's pages (`GrammarTrade`). */
class GrammarTradeCheck : FunSpec({
    val commonWord = GrammarTrade(setOf("common"), price = 20, paidIn = COMMON, seconds = 120, GrammarTrade.Experience(0, 2))
    val rareWord = GrammarTrade(setOf("rare"), price = 30, paidIn = UNCOMMON, seconds = 600, GrammarTrade.Experience(3, 7))

    test("a higher page pays for a lower word") {
        val held = List(15) { COMMON } + List(5) { UNCOMMON }
        check(commonWord.shortfall(held) == 0) { "fifteen commons and five uncommons did not pay for a common word" }
    }

    test("a lower page does not pay for a higher word") {
        val held = List(40) { COMMON } + List(25) { UNCOMMON }
        check(rareWord.shortfall(held) == 5) { "the shortfall counted commons toward a rare word: ${rareWord.shortfall(held)}" }
        check(rareWord.paidWith(held) == null) { "a rare word was paid for with too few pages" }
    }

    test("the cheapest pages that may pay are spent first") {
        val held = listOf(RARE, UNCOMMON, COMMON, UNCOMMON) + List(19) { COMMON }
        val paid = requireNotNull(commonWord.paidWith(held)) { "twenty-three pages did not pay for a common word" }
        check(paid.size == 20) { "spent ${paid.size} pages, not twenty" }
        check(0 !in paid) { "spent the rare page while commons were left" }
    }

    test("a study takes its seconds in ticks") {
        check(rareWord.ticks == 12_000) { "ten minutes was ${rareWord.ticks} ticks" }
    }
})

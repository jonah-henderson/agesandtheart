package co.voik.agesandtheart.preview.authoring.words

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** What the corpus cannot say: worked out once each, through the chains words are taken by, and a word begun where one could fill it. */
@Tags(NEEDS_REGISTRIES)
class GapsCheck : FunSpec({

    val corpus by lazy { WordCorpus(ShippedCorpus.vocabulary) }

    test("each gap is listed once, and a value said through a chain of words is no gap") {
        val gaps = Gaps(corpus).all
        val repeated = gaps.groupBy { it.key }.filterValues { it.size > 1 }.keys
        check(repeated.isEmpty()) { "listed twice: $repeated" }
        val northRising = gaps.filter { it.parameter == "rising" && it.what == "north" }
        check(northRising.isEmpty()) { "north, which rising passes on to the sun, was listed as unsaid" }
        val formerSwitches = gaps.filter { it.what.startsWith("former_") || it.parameter.startsWith("former_") }
        check(formerSwitches.isEmpty()) { "a generator's former switch was listed: ${formerSwitches.map { it.key }}" }
    }

    test("a word begun for a gap offers what it lacks, under a name no word has") {
        val gaps = Gaps(corpus)
        for (gap in gaps.all) {
            val begun = gaps.wordFor(gap) ?: continue
            check(begun.word.senses.single().offers.size == 1) { "the word begun for ${gap.key} offers ${begun.word.senses.single().offers}" }
            check(corpus.words.word(begun.name) == null) { "the word begun for ${gap.key} is named ${begun.name}, which is a word already" }
        }
    }
})

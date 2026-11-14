package co.voik.agesandtheart.preview.authoring.words.ui

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.words.ShippedWords
import co.voik.agesandtheart.age.words.ValueType
import co.voik.agesandtheart.preview.authoring.ui.Questions
import co.voik.agesandtheart.preview.authoring.words.WordFile
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** The word editor over the new model: every word we ship draws, and every concept can be asked for a value. */
@Tags(NEEDS_REGISTRIES)
class WordEditorCheck : FunSpec({

    val corpus by lazy { ShippedWords.corpus }

    test("every section of every word we write draws, and each offer has its row") {
        val sections = Sections(corpus)
        for (name in WordFile.authoredNames()) {
            val candidate = WordFile.read(name).getOrThrow()
            val rows = Section.entries.associateWith { sections.rowsOf(it, candidate) }
            val offerRows = rows.getValue(Section.SENSES).count { it.spot is Spot.OfferAt }
            val offers = candidate.word.senses.sumOf { it.offers.size }
            check(offerRows == offers) { "$name shows $offerRows offers of $offers" }
        }
    }

    test("every concept we ship can be asked for a value, and a flag needs none") {
        for (concept in corpus.words.concepts.values) {
            val questions = Questions()
            var answered = false
            Asking(corpus, questions).aValue(concept, standing = null) { answered = true }
            val isAFlag = concept.values == ValueType.FLAG
            check(if (isAFlag) answered else questions.isOpen) { "${concept.id} asked ${questions.shown?.title} and answered $answered" }
        }
    }
})

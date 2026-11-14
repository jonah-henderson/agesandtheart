package co.voik.agesandtheart.preview.authoring.words.ui

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.words.Outcome
import co.voik.agesandtheart.age.words.ShippedWords
import co.voik.agesandtheart.age.words.ValueType
import co.voik.agesandtheart.preview.authoring.ui.Questions
import co.voik.agesandtheart.preview.authoring.words.Trying
import co.voik.agesandtheart.preview.authoring.words.WordFile
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** The word editor over the new model: every word we ship draws, every concept can be asked for, and a book can be tried. */
@Tags(NEEDS_REGISTRIES)
class WordEditorCheck : FunSpec({

    val corpus by lazy { ShippedWords.corpus }

    test("every section of every word we write draws, with a row for each offer, each acceptance and each effect it makes") {
        val sections = Sections(corpus)
        for (name in WordFile.authoredNames()) {
            val candidate = WordFile.read(name).getOrThrow()
            val rows = Section.entries.associateWith { sections.rowsOf(it, candidate) }
            val senses = rows.getValue(Section.SENSES)
            val senseList = candidate.word.senses
            val offerRows = senses.count { it.spot is Spot.OfferAt }
            check(offerRows == senseList.sumOf { it.offers.size }) { "$name shows $offerRows offers" }
            val acceptanceRows = senses.count { it.spot is Spot.AcceptanceAt }
            check(acceptanceRows == senseList.sumOf { it.accepts.size }) { "$name shows $acceptanceRows acceptances" }
            val made = senseList.sumOf { sense -> sense.accepts.sumOf { (it.outcome as? Outcome.Becomes)?.effects?.size ?: 0 } }
            check(senses.count { it.spot is Spot.AcceptanceEffect } == made) { "$name shows its acceptances' effects short of $made" }
            val reviewed = rows.getValue(Section.REVIEW)
            check(reviewed.size > senseList.size) { "$name's review shows nothing of its offers" }
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

    test("an effect, and what an acceptance makes of any concept, open their questions") {
        val questions = Questions()
        val effects = EffectAsking(corpus, questions)
        effects.anEffect("Something") {}
        check(questions.isOpen) { "asking for an effect opened nothing" }
        for (concept in corpus.words.concepts.values) {
            questions.close()
            effects.aTemplate(concept, part = null) {}
            check(questions.isOpen) { "asking what ${concept.id} becomes opened nothing" }
        }
    }

    test("a book tried with a word in it reads, traces each page, and costs what its pages cost") {
        val trying = Trying(corpus)
        for (name in listOf("porous", "frozen", "hot")) {
            val candidate = WordFile.read(name).getOrThrow()
            val tried = trying.of(candidate, listOf(name, "rock", "age"))
            check(tried.trace.size >= 3) { "$name rock age traced as ${tried.trace}" }
            check(tried.readout.isNotEmpty()) { "$name rock age read as nothing" }
            check(tried.cost > 0) { "$name rock age cost nothing" }
        }
    }
})

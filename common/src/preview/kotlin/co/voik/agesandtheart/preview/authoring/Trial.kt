package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.age.CompositionSpelling
import co.voik.agesandtheart.age.language.Lexicon
import co.voik.agesandtheart.age.language.Reader
import co.voik.agesandtheart.age.language.Reading
import co.voik.agesandtheart.age.language.Writing
import co.voik.agesandtheart.age.resolution.Resolution

/** A book read and resolved **with the word being written standing in for its saved self** — what `^t` shows. */
object Trial {

    /** What a book of [pages] reads as and comes to, or null where it has no `age` page. */
    data class Tried(val reading: Reading, val resolution: Resolution) {
        val spelled: String
            get() = CompositionSpelling.spell(CompositionSpelling.Written(resolution.composition, resolution.template, resolution.instability))
    }

    fun of(candidate: Candidate, pages: List<String>, corpus: Corpus, seed: Long = SEED): Tried? {
        val vocabulary = corpus.vocabulary
        val word = candidate.asWord()
        val lexicon = Lexicon(
            wordNamed = { page -> if (page == candidate.name) word else vocabulary.word(page) },
            structureNamed = { page -> vocabulary.structurePage(page)?.structure },
            tags = vocabulary,
            registries = MinecraftRegistries.worldgen,
        )
        val reading = Reader.read(lexicon, pages, seed) ?: return null
        return Tried(reading, Writing.resolve(lexicon, vocabulary, reading, seed))
    }

    /** The seed a trial is read at unless one is asked for. */
    const val SEED = 7L
}

package co.voik.agesandtheart.age.reading

import co.voik.agesandtheart.age.resolution.Book
import co.voik.agesandtheart.age.resolution.Resolution
import co.voik.agesandtheart.age.resolution.Resolver
import co.voik.agesandtheart.age.resolution.WorldFacts
import co.voik.agesandtheart.age.words.Word
import co.voik.agesandtheart.age.words.Words

/** A row of pages made into an Age: read, drawn, then resolved against the world. */
class Writing(
    private val words: Words,
    private val world: WorldFacts,
    private val membership: Membership,
    /** Whether a word brings what a dangling page may never be drawn onto: a rare material, a reward. */
    private val isGated: (Word) -> Boolean,
) {
    fun resolve(pages: List<String>, seed: Long): Resolution = written(pages, seed).resolution

    /** [pages] read, drawn and resolved at [seed]: everything a book, its readout and the instruments need of them. */
    fun written(pages: List<String>, seed: Long): Written {
        val reading = Reader.read(words, pages, membership, seed)
        val drawn = Draw.of(reading, words, membership, world, isGated, seed)
        return Written(reading, drawn, Resolver.resolve(world, bookOf(reading, drawn), seed))
    }

    /** What resolution is handed of [reading] drawn at [seed]: what landed, what could have, its cost, and its clashes and dangling pages. */
    fun bookOf(reading: Reading, seed: Long): Book = bookOf(reading, Draw.of(reading, words, membership, world, isGated, seed))

    private fun bookOf(reading: Reading, drawn: Drawn): Book {
        return Book(
            landings = drawn.landed,
            possible = drawn.possible,
            template = drawn.template,
            cost = reading.pages.sumOf { page -> words.word(page.written)?.let(words::priceOf) ?: 0 },
            pagesLaid = reading.pages.size,
            chargedBeforeResolving = drawn.flaws,
            unread = reading.pages.filter { it.fate == Fate.Unknown }.map { it.written },
        )
    }
}

/** A row of pages as it was read, what of it landed, and the Age it resolved to. */
data class Written(val reading: Reading, val drawn: Drawn, val resolution: Resolution)

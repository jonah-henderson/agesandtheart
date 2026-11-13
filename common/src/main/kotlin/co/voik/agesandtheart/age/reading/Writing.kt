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
    fun resolve(pages: List<String>, seed: Long): Resolution = Resolver.resolve(world, bookOf(Reader.read(words, pages, membership, seed), seed), seed)

    /** What resolution is handed of [reading] drawn at [seed]: what landed, what could have, its cost, and its clashes and dangling pages. */
    fun bookOf(reading: Reading, seed: Long): Book {
        val drawn = Draw.of(reading, words, membership, world, isGated, seed)
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

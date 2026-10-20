package co.voik.agesandtheart.age.language

import co.voik.agesandtheart.age.Flaw
import co.voik.agesandtheart.age.Register
import co.voik.agesandtheart.age.resolution.Book
import co.voik.agesandtheart.age.resolution.Resolution
import co.voik.agesandtheart.age.resolution.Resolver
import co.voik.agesandtheart.age.resolution.WorldFacts

/** A row of pages made into an Age: read against the lexicon, then resolved against the world. */
object Writing {

    /** [pages] read and resolved at [seed], or null where they spell no book. */
    fun resolve(lexicon: Lexicon, world: WorldFacts, pages: List<String>, seed: Long): Resolution? {
        val reading = Reader.read(lexicon, pages, seed) ?: return null
        return Resolver.resolve(world, bookOf(lexicon, reading), seed)
    }

    /** What resolution is handed of [reading]: what landed, what could have, its cost, and what the reading charged. */
    fun bookOf(lexicon: Lexicon, reading: Reading): Book = Book(
        landings = reading.landings,
        possible = reading.possible,
        template = reading.template,
        cost = costOf(lexicon, reading),
        pagesLaid = reading.pages.size,
        chargedByTheReading = reading.moved.map { page -> moved(reading, page) } +
            reading.impossible.map { Flaw(Register.IMPOSSIBLE, listOf(it.written), aspect = null, tags = emptyList()) },
        unread = reading.unread.map { it.written },
    )

    /** Every page laid costs its ink: a word its price, a structure page its own, and a page that is no word nothing. */
    private fun costOf(lexicon: Lexicon, reading: Reading): Int = reading.pages.sumOf { page ->
        val structure = lexicon.structure(page.written)
        structure?.price ?: lexicon.word(page.written)?.price ?: 0
    }

    private fun moved(reading: Reading, page: PageRead): Flaw {
        val landedIn = reading.landings.firstOrNull { it.page == page.index }?.place?.aspect
        val word = reading.landings.firstOrNull { it.page == page.index }?.word ?: page.written
        return Flaw(Register.REHOMED, listOf(word), landedIn, emptyList())
    }
}

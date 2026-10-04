package co.voik.agesandtheart.desk

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.grammar.Grammar
import net.minecraft.resources.Identifier
import kotlin.random.Random

/**
 * The book the frequency tuner proposes for a signal (design §7.4): a sentence drawn from a writer's own words,
 * the same one for the same seed.
 *
 * **Bindable and sensible, not good.** Every page is one the writer knows, every clause parses as laid, and
 * every modifier is one whose reach includes the part of the world its clause is about — the same reach the
 * grammar guide shows on hover. Nothing is asked of the resolver, so a proposal can still be flawed.
 */
object ProposedBook {

    /** The pages proposed for [seed] from [known], no more than [pageLimit]; empty where nothing can be said. */
    fun of(vocabulary: Vocabulary, known: Collection<Identifier>, seed: Long, pageLimit: Int): List<Identifier> {
        val random = Random(seed)
        val byPage = known.filter { isAPage(vocabulary, it) }.associateBy(Identifier::getPath)
        val nucleus = byPage.keys.sorted().firstOrNull { vocabulary.grammarWord(it) != null && Grammar.isABook(vocabulary, listOf(it)) }
            ?: return emptyList()
        val aimingPages = Aspect.entries.mapNotNull { aspect -> aspect.page?.let { aspect to it } }
            .filter { (_, page) -> page in byPage && vocabulary.word(page) != null }
            .distinctBy { (_, page) -> page }
        val aimingPageNames = aimingPages.map { it.second }.toSet()
        val modifiers = byPage.keys.sorted().filter { it !in aimingPageNames && vocabulary.word(it) != null }

        fun modifiersFor(aspect: Aspect) = modifiers.filter { aspect in (vocabulary.word(it)?.aspects ?: emptySet()) }
        val speaksOfTheWholeAge = modifiers.filter { vocabulary.word(it)?.aspects?.isEmpty() == true }

        val pages = mutableListOf(nucleus)
        val clausesWanted = random.nextInt(1, MOST_CLAUSES + 1)
        var clauses = 0
        for ((aspect, aimingPage) in aimingPages.shuffled(random)) {
            if (clauses == clausesWanted) break
            val room = pageLimit - pages.size - 1
            val candidates = modifiersFor(aspect)
            if (room < 1 || candidates.isEmpty()) continue
            val count = random.nextInt(1, minOf(MOST_MODIFIERS, room, candidates.size) + 1)
            val clause = candidates.shuffled(random).take(count) + aimingPage
            if (!Grammar.parses(vocabulary, pages + clause)) continue
            pages += clause
            clauses++
        }
        if (clauses == 0) return emptyList()

        // Sometimes a word about the whole Age before its nucleus, as `beautiful floating age` has.
        val opening = speaksOfTheWholeAge.takeIf { pages.size < pageLimit && random.nextBoolean() }?.randomOrNull(random)
        val withOpening = opening?.let { listOf(it) + pages }
        val proposed = if (withOpening != null && Grammar.parses(vocabulary, withOpening)) withOpening else pages
        return proposed.mapNotNull(byPage::get)
    }

    private fun isAPage(vocabulary: Vocabulary, word: Identifier): Boolean =
        vocabulary.word(word.path) != null || vocabulary.grammarWord(word.path) != null

    private const val MOST_CLAUSES = 3
    private const val MOST_MODIFIERS = 2
}

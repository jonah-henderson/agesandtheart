package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus.vocabulary
import co.voik.agesandtheart.age.reward.DangerScoring
import co.voik.agesandtheart.book.FoundBookDraft
import co.voik.agesandtheart.book.FoundBookKind
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import kotlin.random.Random

/**
 * The advanced books are where danger's words are shown (design §7.7): in common ink, since the deretheni they lead
 * to is what buys the dearer inks, and never as an Age that pays, since no found Age earns a reward.
 */
@Tags(NEEDS_REGISTRIES)
class DangerBooksCheck : FunSpec({

    val paysAt by lazy { DangerScoring.table.lines.paysAt }

    val advancedPages by lazy {
        (1L..BOOKS_DRAWN).flatMap { seed ->
            val draft = FoundBookDraft.drawn(vocabulary, FoundBookKind.ADVANCED, seed) ?: error("the pack ships no advanced grammar")
            draft.pages
        }.toSet()
    }

    /** The danger books, each with the seed it was drawn at. */
    val dangerBooks by lazy {
        val advanced = vocabulary.generation.grammar(FoundBookKind.ADVANCED.grammar) ?: error("the pack ships no advanced grammar")
        val danger = advanced.copy(start = DANGER_RULE)
        (1L..BOOKS_DRAWN).map { seed -> seed to danger.expand(Random(seed)) }
    }

    fun inkOf(page: String): InkTier {
        val structurePage = vocabulary.structurePages.firstOrNull { it.name == page }
        return vocabulary.word(page)?.let(vocabulary::inkFor) ?: structurePage?.let(vocabulary.ink::tierFor) ?: InkTier.COMMON
    }

    test("the advanced books teach, in common ink, the words of Ages that pay") {
        fun isTaughtInCommonInk(page: String) = page in advancedPages && inkOf(page) == InkTier.COMMON
        val paying = DangerScoring.ANCHORS.filter { (_, rated) -> rated >= paysAt }.map { (sentence, _) -> sentence.split(" ") }
        val taught = paying.filter { pages -> pages.all(::isTaughtInCommonInk) }
        val readout = paying.joinToString("\n") { pages ->
            val missing = pages.filterNot(::isTaughtInCommonInk).map { page -> if (page in advancedPages) "$page (${inkOf(page).key} ink)" else "$page (untaught)" }
            "  ${pages.joinToString(" ")}: ${if (missing.isEmpty()) "taught" else "lacks ${missing.joinToString()}"}"
        }
        check(taught.size >= ROUTES_WANTED) {
            "the advanced books teach ${taught.size} of the paying Ages in common ink, where $ROUTES_WANTED are wanted:\n$readout"
        }
    }

    /** The grammar is the advanced books' to teach, at whatever ink it takes; the words for the danger are what must be cheap. */
    test("the danger books' words are common ink") {
        val structurePages = vocabulary.structurePages.map { it.name }.toSet()
        val words = dangerBooks.flatMap { (_, pages) -> pages }.toSet() - structurePages
        val dearer = words.filter { inkOf(it) != InkTier.COMMON }
        check(dearer.isEmpty()) { "the danger books use dearer ink for ${dearer.joinToString { "$it (${inkOf(it).key})" }}" }
    }

    test("no danger book's own Age pays") {
        val paying = dangerBooks.mapNotNull { (seed, pages) ->
            val score = DangerScoring.dangerOf(pages, seed).score
            "  '${pages.joinToString(" ")}' at seed $seed scores %.2f".format(score).takeIf { score >= paysAt }
        }
        check(paying.isEmpty()) { "${paying.size} danger books would pay, at $paysAt:\n${paying.take(10).joinToString("\n")}" }
    }
})

/** The advanced grammar's rule for the danger books. */
private const val DANGER_RULE = "beset_age"

/** As `BookCheck` draws: more seeds than the grammar has distinct expansions. */
private const val BOOKS_DRAWN = 500L

/** Ages that pay, written wholly in what the advanced books teach in common ink. */
private const val ROUTES_WANTED = 2

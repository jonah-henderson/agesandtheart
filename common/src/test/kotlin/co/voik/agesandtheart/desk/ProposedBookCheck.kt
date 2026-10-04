package co.voik.agesandtheart.desk

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.word.grammar.Grammar
import co.voik.agesandtheart.age.word.grammar.Readout
import co.voik.agesandtheart.preview.authoring.Corpus
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.resources.Identifier

/** What the frequency tuner proposes: a book the writer could bind, the same one for the same signal. */
@Tags(NEEDS_REGISTRIES)
class ProposedBookCheck : FunSpec({

    val vocabulary by lazy { Corpus.load().vocabulary }
    val everyWord by lazy { vocabulary.words.map { it.id } + vocabulary.grammarWords.map { it.id } }
    val seeds = (0..<SIGNALS).map { Tuning.CENTRED.withDial(it % Tuning.DIALS, it % Tuning.STEPS).seed + it }

    test("every proposal parses as laid, within the page limit, from the writer's own words") {
        val bad = seeds.mapNotNull { seed ->
            val proposed = ProposedBook.of(vocabulary, everyWord, seed, PAGE_LIMIT)
            val pages = proposed.map(Identifier::getPath)
            when {
                proposed.isEmpty() -> "seed $seed proposed nothing"
                proposed.size > PAGE_LIMIT -> "seed $seed proposed ${proposed.size} pages: $pages"
                !Grammar.parses(vocabulary, pages) -> "seed $seed proposed a row that does not parse: $pages"
                else -> null
            }
        }
        check(bad.isEmpty()) { bad.joinToString("\n") }
    }

    test("the same signal proposes the same book") {
        val seed = Tuning.CENTRED.seed
        check(ProposedBook.of(vocabulary, everyWord, seed, PAGE_LIMIT) == ProposedBook.of(vocabulary, everyWord, seed, PAGE_LIMIT))
    }

    test("a writer who knows only a few words is proposed only those") {
        val known = listOf("age", "gentle", "pillared", "landmass", "frozen", "sky").mapNotNull { name ->
            everyWord.firstOrNull { it.path == name }
        }
        val proposed = seeds.flatMap { ProposedBook.of(vocabulary, known, it, PAGE_LIMIT) }
        check(proposed.isNotEmpty()) { "nothing was proposed from $known" }
        check(proposed.all { it in known }) { "proposed words the writer does not know: ${proposed - known.toSet()}" }
    }

    test("a writer with no nucleus is proposed nothing") {
        val known = everyWord.filter { it.path in setOf("gentle", "landmass") }
        check(ProposedBook.of(vocabulary, known, Tuning.CENTRED.seed, PAGE_LIMIT).isEmpty())
    }

    test("print a few, to read") {
        for (seed in seeds.take(PRINTED)) {
            val pages = ProposedBook.of(vocabulary, everyWord, seed, PAGE_LIMIT).map(Identifier::getPath)
            val said = Grammar.read(vocabulary, pages)?.let(Readout::of)
            println("PROPOSED ${pages.joinToString(" ")}  —  $said")
        }
    }
}) {
    private companion object {
        const val SIGNALS = 60
        const val PAGE_LIMIT = 9
        const val PRINTED = 12
    }
}

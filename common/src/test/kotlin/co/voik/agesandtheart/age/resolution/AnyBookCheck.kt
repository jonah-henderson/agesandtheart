package co.voik.agesandtheart.age.resolution

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.language.ShippedLexicon
import co.voik.agesandtheart.age.language.Writing
import co.voik.agesandtheart.book.FoundBookDraft
import co.voik.agesandtheart.book.FoundBookKind
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import kotlin.random.Random

/** Whatever a writer lays, the reading and the resolver make an Age of it, and the same Age every time. */
@Tags(NEEDS_REGISTRIES)
class AnyBookCheck : FunSpec({

    fun resolvesTwiceAlike(pages: List<String>, seed: Long) {
        val first = runCatching { Writing.resolve(ShippedLexicon.lexicon, ShippedLexicon.world, pages, seed) }
        check(first.isSuccess) { "'${pages.joinToString(" ")}' at seed $seed threw ${first.exceptionOrNull()}" }
        val again = Writing.resolve(ShippedLexicon.lexicon, ShippedLexicon.world, pages, seed)
        check(first.getOrNull() == again) { "'${pages.joinToString(" ")}' at seed $seed resolved two ways" }
    }

    test("every found book the grammars write resolves, and alike twice") {
        for (kind in FoundBookKind.entries) {
            for (seed in 1L..40L) {
                val draft = FoundBookDraft.drawn(ShippedCorpus.vocabulary, kind, seed) ?: continue
                resolvesTwiceAlike(draft.pages, seed)
            }
        }
    }

    test("random rows of real pages resolve, and alike twice") {
        val authored = ShippedCorpus.vocabulary.authoredWords.map { it.name }
        val structure = listOf("and", "in", "everywhere", "only", "no", "teeming", "scarce")
        val pages = authored + structure + listOf("villages", "trees", "jungle", "stone", "gold_block", "sun", "climate")
        val random = Random(SEED)
        repeat(ROWS) { row ->
            val laid = List(random.nextInt(1, MOST_PAGES)) { pages.random(random) }
            val withAnAge = laid.toMutableList().apply { add(random.nextInt(size + 1), "age") }
            resolvesTwiceAlike(withAnAge, row.toLong())
        }
    }
}) {
    private companion object {
        const val SEED = 0x5EEDL
        const val ROWS = 400
        const val MOST_PAGES = 9
    }
}

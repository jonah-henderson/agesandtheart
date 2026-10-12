package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus.vocabulary
import co.voik.agesandtheart.age.word.grammar.Grammar
import co.voik.agesandtheart.book.FoundBookKind
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import kotlin.random.Random

/**
 * **The unstable book's three kinds stay three** (Jonah, 2026-10-06): slightly, moderately and severely
 * unstable, each with its own count of mistakes, so a reader holding two can tell how far gone each Age is.
 * Each kind's rule is expanded on its own, as [LootBookCheck] does the basic book's. [BookCheck] holds that
 * every unstable book is flawed and none fails; this holds each kind's count.
 */
@Tags(NEEDS_REGISTRIES)
class UnstableBookCheck : FunSpec({

    val grammar by lazy {
        vocabulary.generation.grammar(FoundBookKind.UNSTABLE.grammar) ?: error("the pack ships no unstable book")
    }

    for ((rule, band) in KINDS) {
        test("every $rule book has ${band.first} to ${band.last} distinct mistakes") {
            val strayed = (1L..SEEDS).mapNotNull { seed ->
                val pages = grammar.copy(start = rule).expand(Random(seed))
                val sentence = Grammar.read(vocabulary, pages) ?: error("'${pages.joinToString(" ")}' is not a book")
                val instability = Resolver.resolve(vocabulary, sentence, seed).instability.index
                if (instability in band) null else "seed $seed, $instability flaws: '${pages.joinToString(" ")}'"
            }
            check(strayed.isEmpty()) {
                "${strayed.size} of $SEEDS left the band, the first:\n  ${strayed.take(5).joinToString("\n  ")}"
            }
        }
    }
})

/**
 * Each kind's distinct mistakes (`the-art-design.md` §5.0): slightly unstable one or two, moderately three or
 * four, severely five or six — all short of the seven that fail.
 */
private val KINDS = listOf(
    "slightly_unstable" to 1..2,
    "moderately_unstable" to 3..4,
    "severely_unstable" to 5..6,
)

private const val SEEDS = 200L

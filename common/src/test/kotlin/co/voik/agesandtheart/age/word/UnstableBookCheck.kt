package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus.vocabulary
import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.word.grammar.Grammar
import co.voik.agesandtheart.book.FoundBookKind
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import kotlin.random.Random

/**
 * **The unstable book's three kinds stay three** (Jonah, 2026-10-06): slightly, moderately and severely
 * unstable, each inside its own band, so a reader holding two can tell how far gone each Age is. Each kind's
 * rule is expanded on its own, as [LootBookCheck] does the basic book's. [BookCheck] holds the floor and the
 * collapse for every unstable book; this holds the bands between them.
 */
@Tags(NEEDS_REGISTRIES)
class UnstableBookCheck : FunSpec({

    val grammar by lazy {
        vocabulary.generation.grammar(FoundBookKind.UNSTABLE.grammar) ?: error("the pack ships no unstable book")
    }

    for ((rule, band) in KINDS) {
        test("every $rule book lands in ${band.first}..${band.last}") {
            val strayed = (1L..SEEDS).mapNotNull { seed ->
                val pages = grammar.copy(start = rule).expand(Random(seed))
                val sentence = Grammar.read(vocabulary, pages) ?: error("'${pages.joinToString(" ")}' is not a book")
                val instability = Resolver.resolve(vocabulary, sentence, seed).instability.index
                if (instability in band) null else "seed $seed at $instability: '${pages.joinToString(" ")}'"
            }
            check(strayed.isEmpty()) {
                "${strayed.size} of $SEEDS left the band, the first:\n  ${strayed.take(5).joinToString("\n  ")}"
            }
        }
    }
})

/** Where the wounds start to go on opening, which is what makes an Age severely unstable. */
private val WORSENING_OPENS: Int = firstFloorOf(Manifestation.WORSENING_WOUNDS)

/** Where slightly unstable ends: about one mistake's worth, ours rather than the price list's. */
private const val MOST_FOR_ONE_MISTAKE = 12

private val KINDS = listOf(
    "slightly_unstable" to WOUNDS_AND_PHENOMENA_OPEN..MOST_FOR_ONE_MISTAKE,
    "moderately_unstable" to MOST_FOR_ONE_MISTAKE + 1..<WORSENING_OPENS,
    "severely_unstable" to WORSENING_OPENS..<COLLAPSES_AT,
)

private const val SEEDS = 200L

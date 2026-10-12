package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus.vocabulary
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.age.word.grammar.Grammar
import co.voik.agesandtheart.book.FoundBookKind
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import kotlin.random.Random

/**
 * **The unstable book's three kinds stay three** (Jonah, 2026-10-06): slightly, moderately and severely
 * unstable, each in its own tier, so a reader holding two can tell how far gone each Age is. Each kind's rule
 * is expanded on its own, as [LootBookCheck] does the basic book's. [BookCheck] holds that every unstable
 * book is flawed and none fails; this holds which tier each kind reaches.
 */
@Tags(NEEDS_REGISTRIES)
class UnstableBookCheck : FunSpec({

    val grammar by lazy {
        vocabulary.generation.grammar(FoundBookKind.UNSTABLE.grammar) ?: error("the pack ships no unstable book")
    }

    for ((rule, tier) in KINDS) {
        test("every $rule book's flaws reach tier $tier") {
            val strayed = (1L..SEEDS).mapNotNull { seed ->
                val pages = grammar.copy(start = rule).expand(Random(seed))
                val sentence = Grammar.read(vocabulary, pages) ?: error("'${pages.joinToString(" ")}' is not a book")
                val instability = Resolver.resolve(vocabulary, sentence, seed).instability.index
                val reached = Spending.topTier(instability, ANY_LENGTH)
                if (reached == tier) null else "seed $seed, $instability flaws: '${pages.joinToString(" ")}'"
            }
            check(strayed.isEmpty()) {
                "${strayed.size} of $SEEDS left the band, the first:\n  ${strayed.take(5).joinToString("\n  ")}"
            }
        }
    }
})

/**
 * Each kind in the tier its flaws reach (`notes/instability-tiers-plan.md` §2): one or two mistakes are
 * strange, three or four unsettle, five to seven are hazardous, and none is failing.
 */
private val KINDS = listOf(
    "slightly_unstable" to Spending.STRANGE,
    "moderately_unstable" to Spending.UNSETTLED,
    "severely_unstable" to Spending.HAZARDOUS,
)

/** Long enough that the page cap never binds: the kinds are about how many mistakes, not how short. */
private const val ANY_LENGTH = Int.MAX_VALUE

private const val SEEDS = 200L

package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus.vocabulary
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Phenomena
import co.voik.agesandtheart.age.reward.EarlyGameRareMaterial
import co.voik.agesandtheart.age.reward.EarlyGameRareMaterials
import co.voik.agesandtheart.age.word.generation.GenerationGrammar
import co.voik.agesandtheart.age.word.grammar.Grammar
import co.voik.agesandtheart.book.FoundBookKind
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import kotlin.random.Random

/**
 * **The basic book's two promises** (Jonah, 2026-09-29): a book written to be a rare material's onramp
 * grows that material every time, and a curiosity never grows one by accident.
 *
 * Nothing else tells a player ahead of time what grows a rare material, so an onramp that sometimes did not
 * would teach the wrong gate, and a curiosity that sometimes did would be a material book nobody wrote.
 * Each rule is expanded on its own rather than hoped for among whole books, so every material is asked as
 * often as every other. [BookCheck] holds the rest of what a found book must be.
 */
@Tags(NEEDS_REGISTRIES)
class LootBookCheck : FunSpec({

    val grammar by lazy {
        vocabulary.generation.grammar(FoundBookKind.BASIC.grammar) ?: error("the pack ships no basic book")
    }

    /** What an Age written from [rule] at [seed] grows, and the book it was written from. */
    fun grownFrom(rule: String, seed: Long): Pair<List<String>, Grown> {
        val pages = grammar.copy(start = rule).expand(Random(seed))
        val sentence = Grammar.read(vocabulary, pages) ?: error("'${pages.joinToString(" ")}' is not a book")
        val resolved = Resolver.resolve(vocabulary, sentence, seed)
        val composition = resolved.composition
        // A coherent book inflicts nothing, which is what an empty spending says.
        val materials = EarlyGameRareMaterials.grownIn(composition, seed, Spending(emptyMap()))
        val meteors = Phenomena.claimsIn(composition.optionsFor(Aspect.PHENOMENA, 0)).any { it.value == METEORS }
        return pages to Grown(materials, meteors, resolved.instability.flaws.map { it.toString() })
    }

    for ((rule, material) in MATERIAL_BOOKS) {
        test("every $rule grows ${material ?: "astrite"}") {
            val missed = (1L..SEEDS).mapNotNull { seed ->
                val (pages, grown) = grownFrom(rule, seed)
                val grows = if (material == null) grown.meteors else material in grown.materials
                if (grows) null else "seed $seed: '${pages.joinToString(" ")}' ${grown.flaws}"
            }
            check(missed.isEmpty()) { "${missed.size} of $SEEDS did not grow it, the first:\n  ${missed.take(5).joinToString("\n  ")}" }
        }
    }

    test("no curiosity grows a rare material") {
        val grew = (1L..SEEDS).mapNotNull { seed ->
            val (pages, grown) = grownFrom(CURIOSITY, seed)
            if (grown.materials.isEmpty() && !grown.meteors) null
            else "seed $seed: '${pages.joinToString(" ")}' grew ${grown.materials} meteors=${grown.meteors}"
        }
        check(grew.isEmpty()) { "${grew.size} curiosities grew a material, the first:\n  ${grew.take(5).joinToString("\n  ")}" }
    }
})

private data class Grown(val materials: Set<EarlyGameRareMaterial>, val meteors: Boolean, val flaws: List<String>)

/** Each material book's rule, and the material it promises — null for astrite, whose gate is the meteors. */
private val MATERIAL_BOOKS = listOf(
    "arc_crystal_age" to EarlyGameRareMaterial.ARC_CRYSTAL,
    "phasmium_age" to EarlyGameRareMaterial.GLOOMGRIT,
    "rime_crystal_age" to EarlyGameRareMaterial.RIME,
    "temperstone_age" to EarlyGameRareMaterial.TEMPERSTONE,
    "astrite_age" to null,
)

private const val CURIOSITY = "curiosity_age"
private const val METEORS = "meteors"
private const val SEEDS = 200L

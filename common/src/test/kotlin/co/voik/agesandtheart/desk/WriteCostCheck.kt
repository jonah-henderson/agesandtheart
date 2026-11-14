package co.voik.agesandtheart.desk

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus.vocabulary
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.age.words.Price
import co.voik.agesandtheart.age.words.Word
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs

/**
 * What a page costs, and whether it costs the same on both loaders.
 *
 * **The unit is the loader's**: Fabric counts droplets at 81,000 to the bucket, NeoForge millibuckets at
 * 1,000. Every price is therefore computed *from* that number, and any arithmetic that rounds partway
 * through rounds differently on each — the same word coming out cheaper on one loader than the other, in a
 * mod whose whole point is that one codebase serves both.
 *
 * That is not hypothetical. Priced at 120 units to the bucket, `unitsPerBucket / COST_UNITS_PER_BUCKET` in
 * integers is 675 exactly on Fabric and 8 on NeoForge where the true figure is 8.33 (Jonah, 2026-08-06).
 */
@Tags(NEEDS_REGISTRIES)
class WriteCostCheck : FunSpec({

    // The built-in ones are enough: an authored word carries no registry tag, so `tierFor` falls through
    // to the corpus's own table, which is exactly what is being priced here.
    val registries: RegistryAccess by lazy {
        RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
    }

    /** What the two loaders hand `WriteCost` for one bucket. */
    val fabricDroplets = 81_000L
    val neoforgeMillibuckets = 1_000L

    /**
     * What a word *ought* to cost, as a share of a bucket, before anything rounds — the definition the
     * economy is stated in rather than a number read back out of the code.
     */
    fun idealShare(word: Word, paper: InkTier): Double =
        vocabulary.priceOf(word) * (WriteCost.paperEfficiency(paper)) / WriteCost.COST_UNITS_PER_BUCKET

    /**
     * **Every price lands within one of the loader's own units of the true one.**
     *
     * Compared against the ideal rather than loader-to-loader, because the two loaders have different
     * *granularity* — NeoForge's bucket is a thousand units, so a price near a fifteenth of one can only
     * ever be right to about one and a half per cent, while Fabric's 81,000 lands almost exactly. A same-as-each-
     * other check would have to be loose enough to swallow that gap, and an integer division dropping the
     * NeoForge price by a whole unit fits inside it. Measured against the definition, one unit is one
     * unit, and rounding *up* is the only direction `WriteCost` is allowed to move.
     */
    test("a word costs what the economy says, on either loader's units") {
        val complaints = mutableListOf<String>()
        for (units in listOf(fabricDroplets, neoforgeMillibuckets)) {
            for (paper in InkTier.entries) {
                for (word in vocabulary.authoredWords) {
                    val cost = WriteCost.of(word, vocabulary, registries, paper, units)
                    val share = cost.inkUnits.toDouble() / units
                    val ideal = idealShare(word, paper)
                    val oneUnit = 1.0 / units
                    val drift = share - ideal
                    if (drift < -HAIRS_BREADTH || drift > oneUnit + HAIRS_BREADTH) {
                        complaints += "'${word.name}' on $paper at $units/bucket: $share against $ideal"
                    }
                }
            }
        }
        check(complaints.isEmpty()) {
            "prices are not what the economy says:\n" + complaints.take(6).joinToString("\n")
        }
    }

    test("a structure word costs one unit, never nothing, on any paper") {
        val and = vocabulary.word("and") ?: error("the corpus has no 'and'")
        for (paper in InkTier.entries) {
            check(WriteCost.of(and, vocabulary, registries, paper, 1000L).inkUnits >= 1L) { "'and' was free on $paper" }
        }
    }

    /** The pages every book needs are the cheapest there are: common ink, at the floor. */
    test("age and every aiming page are written in common ink at the least price") {
        val age = vocabulary.word("age") ?: error("the corpus has no 'age' page")
        check(vocabulary.ink.tierFor(age, registries) == InkTier.COMMON) { "'age' demands ${vocabulary.ink.tierFor(age, registries)}" }
        for (aspect in Aspect.entries) {
            val page = vocabulary.word(aspect.page) ?: error("the corpus has no aiming page for $aspect")
            val tier = vocabulary.ink.tierFor(page, registries)
            // The aurora's and the rainbow's pages bring one about, and pay for it as any word choosing it does.
            val meansNothingOfItsOwn = page.senses.all { it.means.isEmpty() && it.offers.isEmpty() }
            val price = vocabulary.priceOf(page)
            check(tier == InkTier.COMMON && (!meansNothingOfItsOwn || price == Price.FLOOR_PRICE)) {
                "the aiming page '${page.name}' costs $price in $tier"
            }
        }
    }

    /**
     * Grammar that does something advanced to generation, and the extremes of a ladder, take fine ink; the
     * step either side of normal takes common (Jonah, 2026-10-11).
     */
    test("the extremes of the grammar take fine ink and the steps beside normal take common") {
        fun tierOf(name: String): InkTier {
            val word = vocabulary.word(name) ?: error("the corpus has no '$name'")
            return vocabulary.ink.tierFor(word, registries)
        }
        val wrong = WANTED_INK.filter { (name, wanted) -> tierOf(name) != wanted }
            .map { (name, wanted) -> "'$name' demands ${tierOf(name)}, not $wanted" }
        check(wrong.isEmpty()) { wrong.joinToString("\n") }
    }

    /**
     * **Five of the commonest page to the bottle**, which is the figure the economy is tuned to (Jonah,
     * 2026-10-21, doubled from ten). A bottle is a third of a bucket, so that page is a fifteenth of one.
     *
     * **The commonest page is a block or a creature said exactly** — a derived word, at home in two parts
     * of the world, so eight units once versatility is charged (world model §9). It used to be any exact
     * word at all, when a page was priced by its tier alone and every exact word cost the same; the anchor
     * has to name a *shape* now, since an exact word can be four units or twenty-four.
     */
    test("the commonest page costs a fifth of a bottle") {
        val ordinary = vocabulary.derivedWords.firstOrNull { vocabulary.priceOf(it) == A_COMMON_PAGE }
            ?: error("the corpus has no ordinary page to price")
        val cost = WriteCost.of(ordinary, vocabulary, registries, InkTier.COMMON, neoforgeMillibuckets)
        val bottles = cost.inkUnits.toDouble() / (neoforgeMillibuckets.toDouble() / WriteCost.BOTTLES_PER_BUCKET)
        check(abs(bottles - A_FIFTH_OF_A_BOTTLE) < CLOSE_ENOUGH) {
            "'${ordinary.name}' costs $bottles of a bottle, where the economy is tuned to $A_FIFTH_OF_A_BOTTLE"
        }
    }

    /** Better paper is cheaper and never free, and never changes which ink a word demands. */
    test("better paper discounts the amount and never the tier") {
        for (word in vocabulary.authoredWords) {
            val cheap = WriteCost.of(word, vocabulary, registries, InkTier.COMMON, fabricDroplets)
            val dear = WriteCost.of(word, vocabulary, registries, InkTier.MASTERWORK, fabricDroplets)
            check(dear.inkUnits <= cheap.inkUnits) { "'${word.name}' costs more on better paper" }
            check(dear.inkUnits >= 1L) { "'${word.name}' is free on masterwork paper" }
            check(dear.inkTier == cheap.inkTier) {
                "'${word.name}' demands a different ink on better paper: ${cheap.inkTier} then ${dear.inkTier}"
            }
        }
    }
})

/** Floating-point slack, well under the rounding that would matter to a price. */
private const val CLOSE_ENOUGH = 0.005

/** What the commonest page is priced at: exact, and at home in two parts of the world. */
private const val A_COMMON_PAGE = 8

private const val A_FIFTH_OF_A_BOTTLE = 0.2

private val WANTED_INK = mapOf(
    "and" to InkTier.FINE,
    "only" to InkTier.FINE,
    "no" to InkTier.FINE,
    "in" to InkTier.FINE,
    "everywhere" to InkTier.FINE,
    "scarce" to InkTier.FINE,
    "few" to InkTier.COMMON,
    "plentiful" to InkTier.COMMON,
    "teeming" to InkTier.FINE,
    "minuscule" to InkTier.FINE,
    "small" to InkTier.COMMON,
    "large" to InkTier.COMMON,
    "colossal" to InkTier.FINE,
)

/** Floating-point noise, far below one unit of either loader. */
private const val HAIRS_BREADTH = 1e-9

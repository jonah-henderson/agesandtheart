package co.voik.agesandtheart.desk

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.age.word.Tier
import co.voik.agesandtheart.age.word.Vocabulary
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

    val vocabulary by lazy {
        Vocabulary.load(MinecraftRegistries.shippedData()).also {
            check(it.problems.isEmpty()) { "the corpus would not load: ${it.problems}" }
        }
    }

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
    fun idealShare(tier: Tier, paper: InkTier): Double =
        tier.cost * (WriteCost.paperEfficiency(paper)) / WriteCost.COST_UNITS_PER_BUCKET

    /**
     * **Every price lands within one of the loader's own units of the true one.**
     *
     * Compared against the ideal rather than loader-to-loader, because the two loaders have different
     * *granularity* — NeoForge's bucket is a thousand units, so a price near a thirtieth of one can only
     * ever be right to about three per cent, while Fabric's 81,000 lands almost exactly. A same-as-each-
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
                    val ideal = idealShare(word.tier, paper)
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

    /**
     * **Ten exact words to the bottle**, which is the figure the economy was tuned to (Jonah, 2026-08-06).
     * A bottle is a third of a bucket, so an exact word is a thirtieth of one.
     */
    test("an exact word costs a tenth of a bottle") {
        val exact = vocabulary.authoredWords.firstOrNull { it.tier == Tier.EXACT }
            ?: error("the corpus has no exact word to price")
        val cost = WriteCost.of(exact, vocabulary, registries, InkTier.COMMON, neoforgeMillibuckets)
        val bottles = cost.inkUnits.toDouble() / (neoforgeMillibuckets.toDouble() / WriteCost.BOTTLES_PER_BUCKET)
        check(abs(bottles - A_TENTH_OF_A_BOTTLE) < CLOSE_ENOUGH) {
            "'${exact.name}' costs $bottles of a bottle, where the economy is tuned to $A_TENTH_OF_A_BOTTLE"
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

private const val A_TENTH_OF_A_BOTTLE = 0.1

/** Floating-point noise, far below one unit of either loader. */
private const val HAIRS_BREADTH = 1e-9

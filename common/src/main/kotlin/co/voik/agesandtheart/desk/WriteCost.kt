package co.voik.agesandtheart.desk

import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.Word
import net.minecraft.core.RegistryAccess

/**
 * What one page costs: which ink, how much of it, and a sheet of the chosen paper.
 *
 * Two independent axes meet here, and keeping them apart is the point (design §7.1.1). **Which** ink a
 * word demands comes from the referent's tags — a diamond needs the good ink however vaguely you use it.
 * **How much** comes from the word's own cost, which is the resolver's number finally being read by
 * something. Paper discounts the amount without ever touching the tier, so it eases the economy but can
 * never unlock a word.
 */
data class WriteCost(
    val inkTier: InkTier,
    val inkUnits: Long,
    val paperTier: InkTier,
    val sheets: Int = 1,
) {
    companion object {
        /**
         * Ink for one unit of a word's cost, as a fraction of a bucket.
         *
         * **Ten exact words to the bottle**, which is the figure a walk asked for (Jonah, 2026-08-06). A
         * bottle is a third of a bucket, an exact word costs four units, so 120 units to the bucket puts a
         * page at a tenth of a bottle — and an evocative word at a fortieth.
         *
         * It was 8, which made a bucket write two exact words. Ink is meant to be the thing a writer
         * budgets across a *book*, and at that price a single page was a trip to the cauldron.
         */
        const val COST_UNITS_PER_BUCKET = 120

        /**
         * Vanilla's own: a cauldron holds three bottles, and a page is priced in fractions of one.
         *
         * A **ratio**, not a quantity, which is what makes it safe to write down. How many *units* a
         * bucket holds is the loader's business and differs between the two; how many bottles fill one is
         * a fact about Minecraft. Anything converting for display divides the loader's own capacity by
         * this rather than assuming either.
         */
        const val BOTTLES_PER_BUCKET = 3

        /** What better paper saves. Never a gate: the worst paper still writes anything. */
        private val PAPER_EFFICIENCY = mapOf(
            InkTier.COMMON to 1.0,
            InkTier.FINE to 0.75,
            InkTier.MASTERWORK to 0.5,
        )

        /** What [paperTier] multiplies a word's cost by — read by the checks that price the economy. */
        fun paperEfficiency(paperTier: InkTier): Double = PAPER_EFFICIENCY[paperTier] ?: 1.0

        fun of(
            word: Word,
            vocabulary: Vocabulary,
            registries: RegistryAccess,
            paperTier: InkTier,
            unitsPerBucket: Long,
        ): WriteCost {
            val required = vocabulary.ink.tierFor(word, registries)
            // **In doubles, and that is not fussiness.** `unitsPerBucket` is the loader's — 81,000
            // droplets on Fabric, 1,000 millibuckets on NeoForge — so an integer division here truncates
            // differently on each: at 120 units to the bucket, NeoForge lands on 8 where the true figure
            // is 8.33 and the same word comes out cheaper on one loader than the other.
            val perUnit = unitsPerBucket.toDouble() / COST_UNITS_PER_BUCKET
            val efficiency = paperEfficiency(paperTier)
            // Rounded up, so better paper makes a word cheaper but never free.
            val units = Math.ceil(word.tier.cost * perUnit * efficiency).toLong().coerceAtLeast(1L)
            return WriteCost(required, units, paperTier)
        }
    }
}

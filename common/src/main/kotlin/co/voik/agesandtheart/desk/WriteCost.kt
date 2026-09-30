package co.voik.agesandtheart.desk

import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.Word
import net.minecraft.core.RegistryAccess

/**
 * What one page costs in ink: which ink, and how much of it on the chosen paper.
 *
 * Two independent axes meet here, and keeping them apart is the point (design §7.1.1). **Which** ink a
 * word demands comes from the referent's tags — a diamond needs the good ink however vaguely you use it.
 * **How much** comes from [Word.price], which is specificity times versatility (world model §9) and the
 * same number a book's cost is the sum of — but for a bare page with [Word.unaimed] chances, which a book
 * charges at [Word.barePrice]. Paper discounts the amount without ever touching the tier, so
 * it eases the economy but can never unlock a word. The sheets a book takes are the book's business — see
 * [sheetsFor] and [BookCost].
 */
data class WriteCost(
    val inkTier: InkTier,
    val inkUnits: Long,
    val paperTier: InkTier,
) {
    companion object {
        /**
         * Ink for one unit of a word's cost, as a fraction of a bucket.
         *
         * **Ten of the commonest page to the bottle**, which is the figure a walk asked for (Jonah,
         * 2026-08-06). A bottle is a third of a bucket, and the page a writer most often writes is a block
         * or a creature said exactly — eight units, being exact and at home in two parts of the world — so
         * 240 units to the bucket puts one at a tenth of a bottle.
         *
         * **It was 120, when a page was priced by its tier alone.** Versatility is charged now
         * ([Word.price]), which doubles the commonest page and halves nothing, so the constant doubles to
         * keep the economy at the weight it was walked at. What moved is the *spread*: a word at home in
         * one place is now half the price of one at home in two, where before they were the same.
         */
        const val COST_UNITS_PER_BUCKET = 240

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

        /**
         * How many words one sheet carries (Jonah, 2026-09-18, to be playtested). Better paper has to be
         * **strictly superior for the effort of getting it**, so beyond the ink it saves, a sheet of it
         * carries more than one word.
         */
        private val WORDS_PER_SHEET = mapOf(
            InkTier.COMMON to 1,
            InkTier.FINE to 4,
            InkTier.MASTERWORK to 10,
        )

        fun wordsPerSheet(paperTier: InkTier): Int = WORDS_PER_SHEET[paperTier] ?: 1

        /** The sheets [pages] new pages take on [paperTier], rounded up — a part-used sheet is still spent. */
        fun sheetsFor(pages: Int, paperTier: InkTier): Int {
            val perSheet = wordsPerSheet(paperTier)
            return (pages + perSheet - 1) / perSheet
        }

        /** A structural word's ink on [paperTier]: one unit of cost, the least any page is priced at. */
        fun structural(paperTier: InkTier, unitsPerBucket: Long): Long {
            val perUnit = unitsPerBucket.toDouble() / COST_UNITS_PER_BUCKET
            return Math.ceil(perUnit * paperEfficiency(paperTier)).toLong().coerceAtLeast(1L)
        }

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
            val units = Math.ceil(word.price * perUnit * efficiency).toLong().coerceAtLeast(1L)
            return WriteCost(required, units, paperTier)
        }
    }
}

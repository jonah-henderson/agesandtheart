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
         * Ink for one unit of a word's cost, as a fraction of a bucket. An evocative word costs one unit,
         * an exact one four per aspect — so a bucket writes eight of the cheapest or two of the dearest.
         */
        private const val COST_UNITS_PER_BUCKET = 8

        /** What better paper saves. Never a gate: the worst paper still writes anything. */
        private val PAPER_EFFICIENCY = mapOf(
            InkTier.COMMON to 1.0,
            InkTier.FINE to 0.75,
            InkTier.MASTERWORK to 0.5,
        )

        fun of(
            word: Word,
            vocabulary: Vocabulary,
            registries: RegistryAccess,
            paperTier: InkTier,
            unitsPerBucket: Long,
        ): WriteCost {
            val required = vocabulary.ink.tierFor(word, registries)
            val perUnit = unitsPerBucket / COST_UNITS_PER_BUCKET
            val efficiency = PAPER_EFFICIENCY[paperTier] ?: 1.0
            // Rounded up, so better paper makes a word cheaper but never free.
            val units = Math.ceil(word.tier.cost * perUnit * efficiency).toLong().coerceAtLeast(1L)
            return WriteCost(required, units, paperTier)
        }
    }
}

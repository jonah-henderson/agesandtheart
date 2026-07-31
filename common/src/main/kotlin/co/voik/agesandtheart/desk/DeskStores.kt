package co.voik.agesandtheart.desk

import co.voik.agesandtheart.age.word.InkTier
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier

/**
 * Every page the desk holds, as word → count.
 *
 * Pages of the same word are identical items, so a count is lossless — and it is what makes "unlimited"
 * affordable, since a thousand pages of `flat` cost one entry rather than a thousand stacks.
 */
data class PageArchive(private val counts: Map<Identifier, Int>) {
    val words: Set<Identifier> get() = counts.keys

    val total: Int get() = counts.values.sum()

    fun count(word: Identifier): Int = counts[word] ?: 0

    fun has(word: Identifier): Boolean = count(word) > 0

    fun with(word: Identifier, added: Int): PageArchive =
        PageArchive(counts + (word to (count(word) + added).coerceAtLeast(0)))

    /** @return the archive without [taken] copies, or null if it does not hold that many. */
    fun without(word: Identifier, taken: Int): PageArchive? {
        val remaining = count(word) - taken
        if (remaining < 0) return null
        return PageArchive(if (remaining == 0) counts - word else counts + (word to remaining))
    }

    companion object {
        val EMPTY = PageArchive(emptyMap())

        val CODEC: Codec<PageArchive> = Codec.unboundedMap(Identifier.CODEC, Codec.INT)
            .xmap(::PageArchive) { it.counts }
    }
}

/**
 * The three ink tanks and the three paper stacks.
 *
 * Ink is counted in the units both loaders' fluid APIs use, so nothing has to convert at the boundary;
 * paper is counted in sheets. Both are capped, because the desk is a workspace rather than a warehouse.
 */
data class DeskStores(
    private val ink: Map<InkTier, Long>,
    private val paper: Map<InkTier, Int>,
) {
    fun ink(tier: InkTier): Long = ink[tier] ?: 0L

    fun paper(tier: InkTier): Int = paper[tier] ?: 0

    /** Room left in a tank. [capacity] is passed in because the unit belongs to the loader, not to us. */
    fun inkSpace(tier: InkTier, capacity: Long): Long = capacity - ink(tier)

    fun paperSpace(tier: InkTier): Int = PAPER_CAPACITY - paper(tier)

    /** @return this plus what fits, and how much did not. */
    fun addingInk(tier: InkTier, amount: Long, capacity: Long): Pair<DeskStores, Long> {
        val accepted = amount.coerceAtMost(inkSpace(tier, capacity)).coerceAtLeast(0)
        return copy(ink = ink + (tier to ink(tier) + accepted)) to (amount - accepted)
    }

    /** Sets a tank outright. For a pipe, which computes the new level itself and hands it over. */
    fun withInk(tier: InkTier, amount: Long, capacity: Long): DeskStores =
        copy(ink = ink + (tier to amount.coerceIn(0, capacity)))

    fun addingPaper(tier: InkTier, sheets: Int): Pair<DeskStores, Int> {
        val accepted = sheets.coerceAtMost(paperSpace(tier)).coerceAtLeast(0)
        return copy(paper = paper + (tier to paper(tier) + accepted)) to (sheets - accepted)
    }

    /** @return this less the cost, or null if it cannot be paid — so a partial spend is unrepresentable. */
    fun spending(inkTier: InkTier, inkUnits: Long, paperTier: InkTier, sheets: Int): DeskStores? {
        if (ink(inkTier) < inkUnits || paper(paperTier) < sheets) return null
        return copy(
            ink = ink + (inkTier to ink(inkTier) - inkUnits),
            paper = paper + (paperTier to paper(paperTier) - sheets),
        )
    }

    companion object {
        /** Sheets per quality. Three digits so the readout never has to shorten a number. */
        const val PAPER_CAPACITY = 999

        val EMPTY = DeskStores(emptyMap(), emptyMap())

        private val TIER_CODEC: Codec<InkTier> = InkTier.CODEC

        val CODEC: Codec<DeskStores> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.unboundedMap(TIER_CODEC, Codec.LONG).optionalFieldOf("ink", emptyMap())
                    .forGetter { it.ink },
                Codec.unboundedMap(TIER_CODEC, Codec.INT).optionalFieldOf("paper", emptyMap())
                    .forGetter { it.paper },
            ).apply(instance, ::DeskStores)
        }
    }
}

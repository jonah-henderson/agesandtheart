package co.voik.agesandtheart.desk

import co.voik.agesandtheart.age.word.InkTier
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * The three ink tanks, the three paper stacks, and the binding.
 *
 * Ink is counted in the units both loaders' fluid APIs use, so nothing has to convert at the boundary;
 * paper is counted in sheets. All are capped, because the desk is a workspace rather than a warehouse.
 */
data class DeskStores(
    private val ink: Map<InkTier, Long>,
    private val paper: Map<InkTier, Int>,
    private val binding: Int = 0,
) {
    fun ink(tier: InkTier): Long = ink[tier] ?: 0L

    fun paper(tier: InkTier): Int = paper[tier] ?: 0

    fun binding(): Int = binding

    fun bindingSpace(): Int = BINDING_CAPACITY - binding

    /** @return this plus what fits, and how much did not. */
    fun addingBinding(count: Int): Pair<DeskStores, Int> {
        val accepted = count.coerceAtMost(bindingSpace()).coerceAtLeast(0)
        return copy(binding = binding + accepted) to (count - accepted)
    }

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

    /**
     * @return this less the whole of [cost], or null if any of it cannot be paid — so a book half-paid for
     * is unrepresentable.
     */
    fun paying(cost: BookCost): DeskStores? {
        if (!cost.affordableFrom(this)) return null
        val inkLeft = cost.ink.entries.fold(ink) { tanks, (tier, units) -> tanks + (tier to ink(tier) - units) }
        return copy(
            ink = inkLeft,
            paper = paper + (cost.paper to paper(cost.paper) - cost.sheets),
            binding = binding - cost.bindings,
        )
    }

    companion object {
        /** Sheets per quality. Three digits so the readout never has to shorten a number. */
        const val PAPER_CAPACITY = 999

        /** Bindings, on the same three-digit reasoning. */
        const val BINDING_CAPACITY = 999

        val EMPTY = DeskStores(emptyMap(), emptyMap(), 0)

        private val TIER_CODEC: Codec<InkTier> = InkTier.CODEC

        val CODEC: Codec<DeskStores> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.unboundedMap(TIER_CODEC, Codec.LONG).optionalFieldOf("ink", emptyMap())
                    .forGetter { it.ink },
                Codec.unboundedMap(TIER_CODEC, Codec.INT).optionalFieldOf("paper", emptyMap())
                    .forGetter { it.paper },
                Codec.INT.optionalFieldOf("binding", 0).forGetter { it.binding },
            ).apply(instance, ::DeskStores)
        }
    }
}

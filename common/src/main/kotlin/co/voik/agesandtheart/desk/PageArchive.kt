package co.voik.agesandtheart.desk

import com.mojang.serialization.Codec
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier

/**
 * Every page an archive holds, as word → count.
 *
 * Pages of the same word are identical items, so a count is lossless — and it is what makes "unlimited"
 * affordable, since a thousand pages of `flat` cost one entry rather than a thousand stacks.
 */
data class PageArchive(private val counts: Map<Identifier, Int>) {
    val words: Set<Identifier> get() = counts.keys

    val isEmpty: Boolean get() = counts.isEmpty()

    /** Every page, counted — what an archive carried as an item says it holds. */
    val total: Int get() = counts.values.sum()

    fun count(word: Identifier): Int = counts[word] ?: 0

    fun asMap(): Map<Identifier, Int> = counts

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

        val STREAM_CODEC: StreamCodec<ByteBuf, PageArchive> =
            ByteBufCodecs.map(::LinkedHashMap, Identifier.STREAM_CODEC, ByteBufCodecs.VAR_INT)
                .map(::PageArchive) { LinkedHashMap(it.counts) }
    }
}

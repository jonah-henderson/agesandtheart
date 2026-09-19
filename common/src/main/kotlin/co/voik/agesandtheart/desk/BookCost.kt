package co.voik.agesandtheart.desk

import co.voik.agesandtheart.age.word.InkTier
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec

/**
 * What writing one missing page would cost: the ink its word demands, and how much of it on each paper.
 *
 * Sent to the screen per page still to be written, so the paper and ink choices can be priced on the
 * client with no round trip — the server has already done the part only it can, which is reading the
 * word's tags and cost number out of the corpus.
 */
data class PagePrice(val required: InkTier, val unitsOnPaper: Map<InkTier, Long>) {
    fun units(paper: InkTier): Long = unitsOnPaper[paper] ?: 0L

    companion object {
        private val TIER: StreamCodec<ByteBuf, InkTier> =
            ByteBufCodecs.idMapper({ InkTier.entries[it] }, InkTier::ordinal)

        val STREAM_CODEC: StreamCodec<ByteBuf, PagePrice> = StreamCodec.composite(
            TIER, PagePrice::required,
            ByteBufCodecs.map(::LinkedHashMap, TIER, ByteBufCodecs.VAR_LONG)
                .map({ it as Map<InkTier, Long> }, { LinkedHashMap(it) }),
            PagePrice::unitsOnPaper,
            ::PagePrice,
        )
    }
}

/**
 * What binding a book costs, all of it: the ink for every page still to be written, the sheets they go on,
 * and the binding (design: the writer's desk redesign, "Writing is a template").
 *
 * Pages already written — in an archive in the room or in the writer's inventory — are not here at all:
 * a page drawn is a page not written.
 */
data class BookCost(val ink: Map<InkTier, Long>, val paper: InkTier, val sheets: Int, val bindings: Int) {

    fun affordableFrom(stores: DeskStores): Boolean = affordable(stores::ink, stores::paper, stores.binding())

    /** The same question asked of stock held anywhere — the screen asks it of what the desk last sent. */
    fun affordable(inkHeld: (InkTier) -> Long, paperHeld: (InkTier) -> Int, bindingsHeld: Int): Boolean {
        val hasTheInk = ink.all { (tier, units) -> inkHeld(tier) >= units }
        val hasThePaper = paperHeld(paper) >= sheets
        return hasTheInk && hasThePaper && bindingsHeld >= bindings
    }

    companion object {
        /** One binding per book, whatever it holds. */
        const val BINDINGS_PER_BOOK = 1

        /** [toWrite] priced on [paper], each page in exactly the ink its word demands. */
        fun of(toWrite: List<PagePrice>, paper: InkTier): BookCost {
            val ink = toWrite
                .groupBy { it.required }
                .mapValues { (_, pages) -> pages.sumOf { it.units(paper) } }
            return BookCost(ink, paper, WriteCost.sheetsFor(toWrite.size, paper), BINDINGS_PER_BOOK)
        }
    }
}

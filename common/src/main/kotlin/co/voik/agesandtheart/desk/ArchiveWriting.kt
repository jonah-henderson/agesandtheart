package co.voik.agesandtheart.desk

import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.age.word.learnedWords
import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level

/** What the closest writer's desk holds that writing a loose page could spend: ink by tier, and common paper. */
data class WritingStock(val ink: Map<InkTier, Long>, val paper: Int) {

    companion object {
        private val TIER: StreamCodec<ByteBuf, InkTier> =
            ByteBufCodecs.idMapper({ InkTier.entries[it] }, InkTier::ordinal)

        val STREAM_CODEC: StreamCodec<ByteBuf, WritingStock> = StreamCodec.composite(
            ByteBufCodecs.map(::LinkedHashMap, TIER, ByteBufCodecs.VAR_LONG)
                .map({ it as Map<InkTier, Long> }, { LinkedHashMap(it) }),
            WritingStock::ink,
            ByteBufCodecs.VAR_INT, WritingStock::paper,
            ::WritingStock,
        )
    }
}

/** Why a page cannot be written from an archive just now; [key] is the suffix of a lang key. */
enum class WritingObstacle(val key: String) {
    NO_DESK("no_desk"),
    NOT_PRICED("not_priced"),
    SHORT_OF_INK("short_of_ink"),
    SHORT_OF_PAPER("short_of_paper"),
}

/**
 * Writing a loose page into an archive, paid for from the writer's desk nearest it.
 *
 * Always on regular paper, a sheet to a page: physical paper stays one to one, so none of the whole-book
 * rule that a better sheet carries several words applies here.
 */
object ArchiveWriting {

    val PAPER = InkTier.COMMON

    /** The whole cost of one page: the ink its word demands and a sheet, with no binding. */
    fun costOf(price: PagePrice): BookCost =
        BookCost(mapOf(price.required to price.units(PAPER)), PAPER, sheets = 1, bindings = 0)

    /** What stands in the way of writing a page priced [price] from [stock], or null where nothing does. */
    fun obstacleTo(price: PagePrice?, stock: WritingStock?): WritingObstacle? {
        if (stock == null) return WritingObstacle.NO_DESK
        if (price == null) return WritingObstacle.NOT_PRICED
        val cost = costOf(price)
        val hasThePaper = stock.paper >= cost.sheets
        val hasTheInk = cost.ink.all { (tier, units) -> (stock.ink[tier] ?: 0L) >= units }
        return when {
            !hasThePaper -> WritingObstacle.SHORT_OF_PAPER
            !hasTheInk -> WritingObstacle.SHORT_OF_INK
            else -> null
        }
    }

    fun stockOf(desk: WritersDeskBlockEntity): WritingStock =
        WritingStock(InkTier.entries.associateWith(desk.stores::ink), desk.stores.paper(PAPER))

    /** The desk nearest [pos] within [radius], or null; a tie goes to whichever the scan met first. */
    fun closestDesk(level: Level, pos: BlockPos, radius: Int): WritersDeskBlockEntity? {
        var closest: WritersDeskBlockEntity? = null
        var closestDistance = Double.MAX_VALUE
        val cursor = BlockPos.MutableBlockPos()
        for (x in -radius..radius) for (y in -radius..radius) for (z in -radius..radius) {
            cursor.setWithOffset(pos, x, y, z)
            val desk = level.getBlockEntity(cursor) as? WritersDeskBlockEntity ?: continue
            val distance = cursor.distSqr(pos).toDouble()
            if (distance < closestDistance) {
                closest = desk
                closestDistance = distance
            }
        }
        return closest
    }

    /** The price of every word [player] knows, which is all they can write. */
    fun pricesFor(player: ServerPlayer): Map<Identifier, PagePrice> =
        player.learnedWords.words.associateWith { WritersDeskMenu.pricePage(player, it) }
}

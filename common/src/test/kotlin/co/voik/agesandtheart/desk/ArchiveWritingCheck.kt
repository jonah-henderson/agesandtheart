package co.voik.agesandtheart.desk

import co.voik.agesandtheart.age.word.InkTier
import io.kotest.core.spec.style.FunSpec
import io.netty.buffer.Unpooled
import net.minecraft.resources.Identifier

/**
 * That a page can be written from an archive exactly when the nearest desk can pay for all of it, and that
 * what the screen is told survives the wire. Both halves of the decision — the screen's greying and the
 * server's refusal — are this one function.
 */
class ArchiveWritingCheck : FunSpec({

    val commonWord = PagePrice(InkTier.COMMON, mapOf(InkTier.COMMON to 8L, InkTier.FINE to 6L, InkTier.MASTERWORK to 4L))
    val fineWord = PagePrice(InkTier.FINE, mapOf(InkTier.COMMON to 20L))

    fun stock(common: Long = 0, fine: Long = 0, paper: Int = 0) =
        WritingStock(mapOf(InkTier.COMMON to common, InkTier.FINE to fine), paper)

    test("a page is one sheet of regular paper and the ink its word demands, never a binding") {
        val cost = ArchiveWriting.costOf(fineWord)
        check(cost.paper == InkTier.COMMON && cost.sheets == 1) { "A page cost $cost" }
        check(cost.ink == mapOf(InkTier.FINE to 20L)) { "A fine word's ink was ${cost.ink}" }
        check(cost.bindings == 0) { "A loose page was charged a binding" }
    }

    test("a page is writable only when a desk is in range and it can pay all of it") {
        check(ArchiveWriting.obstacleTo(commonWord, null) == WritingObstacle.NO_DESK) { "No desk was not named" }
        check(ArchiveWriting.obstacleTo(null, stock(100, 100, 100)) == WritingObstacle.NOT_PRICED) { "An unpriced word was writable" }
        check(ArchiveWriting.obstacleTo(commonWord, stock(common = 7, paper = 1)) == WritingObstacle.SHORT_OF_INK) { "7 of 8 was enough ink" }
        check(ArchiveWriting.obstacleTo(commonWord, stock(common = 8, paper = 0)) == WritingObstacle.SHORT_OF_PAPER) { "No paper was enough" }
        check(ArchiveWriting.obstacleTo(commonWord, stock(common = 8, paper = 1)) == null) { "Exactly enough was refused" }
    }

    test("fine ink is not a substitute for common, nor common for fine") {
        check(ArchiveWriting.obstacleTo(commonWord, stock(common = 0, fine = 100, paper = 5)) == WritingObstacle.SHORT_OF_INK) {
            "Fine ink stood in for common"
        }
        check(ArchiveWriting.obstacleTo(fineWord, stock(common = 100, fine = 19, paper = 5)) == WritingObstacle.SHORT_OF_INK) {
            "Common ink stood in for fine"
        }
    }

    test("what the screen is told survives the wire") {
        val told = stock(common = 12, fine = 3, paper = 40)
        val stockBuffer = Unpooled.buffer()
        ArchiveStockPayload.STREAM_CODEC.encode(stockBuffer, ArchiveStockPayload(told))
        check(ArchiveStockPayload.STREAM_CODEC.decode(stockBuffer).stock == told) { "A desk's stock changed in transit" }

        val guided = Unpooled.buffer()
        ArchiveStockPayload.STREAM_CODEC.encode(guided, ArchiveStockPayload(told, showsWhereWordsApply = true))
        check(ArchiveStockPayload.STREAM_CODEC.decode(guided).showsWhereWordsApply) { "A desk's grammar guide was lost in transit" }

        val noDesk = Unpooled.buffer()
        ArchiveStockPayload.STREAM_CODEC.encode(noDesk, ArchiveStockPayload(null))
        check(ArchiveStockPayload.STREAM_CODEC.decode(noDesk).stock == null) { "No desk arrived as a desk" }

        val prices = mapOf(Identifier.parse("agesandtheart:diamond") to fineWord, Identifier.parse("agesandtheart:stone") to commonWord)
        val priceBuffer = Unpooled.buffer()
        ArchivePricesPayload.STREAM_CODEC.encode(priceBuffer, ArchivePricesPayload(prices))
        check(ArchivePricesPayload.STREAM_CODEC.decode(priceBuffer).prices == prices) { "Prices changed in transit" }
    }
})

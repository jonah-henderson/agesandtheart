package co.voik.agesandtheart.desk

import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.learnedWords
import net.minecraft.util.Prediction
import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.content.PageItem
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer

/** What the open archive holds, sent whenever it changes. */
data class ArchiveSyncPayload(val pages: PageArchive) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<ArchiveSyncPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<ArchiveSyncPayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "archive_sync"),
        )

        val STREAM_CODEC: StreamCodec<ByteBuf, ArchiveSyncPayload> =
            PageArchive.STREAM_CODEC.map(::ArchiveSyncPayload, ArchiveSyncPayload::pages)
    }
}

/** Take pages of [word] out of the open archive: one, or a stackful when [wholeStack]. */
data class ArchiveWithdrawPayload(val word: Identifier, val wholeStack: Boolean) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<ArchiveWithdrawPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<ArchiveWithdrawPayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "archive_withdraw"),
        )

        val STREAM_CODEC: StreamCodec<ByteBuf, ArchiveWithdrawPayload> = StreamCodec.composite(
            Identifier.STREAM_CODEC, ArchiveWithdrawPayload::word,
            ByteBufCodecs.BOOL, ArchiveWithdrawPayload::wholeStack,
            ::ArchiveWithdrawPayload,
        )
    }
}

/** What every word the viewer knows costs to write as a page, sent when what they know changes. */
data class ArchivePricesPayload(val prices: Map<Identifier, PagePrice>) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<ArchivePricesPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<ArchivePricesPayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "archive_prices"),
        )

        val STREAM_CODEC: StreamCodec<ByteBuf, ArchivePricesPayload> =
            ByteBufCodecs.map(::LinkedHashMap, Identifier.STREAM_CODEC, PagePrice.STREAM_CODEC)
                .map({ ArchivePricesPayload(it) }, { LinkedHashMap(it.prices) })
    }
}

/** What the writer's desk nearest the open archive holds, or null where there is none in range. */
data class ArchiveStockPayload(val stock: WritingStock?) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<ArchiveStockPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<ArchiveStockPayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "archive_stock"),
        )

        val STREAM_CODEC: StreamCodec<ByteBuf, ArchiveStockPayload> =
            ByteBufCodecs.optional(WritingStock.STREAM_CODEC)
                .map({ ArchiveStockPayload(it.orElse(null)) }, { java.util.Optional.ofNullable(it.stock) })
    }
}

/** Write a page of [word] into the open archive, paid for at the nearest writer's desk. */
data class ArchiveWritePayload(val word: Identifier) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<ArchiveWritePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<ArchiveWritePayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "archive_write"),
        )

        val STREAM_CODEC: StreamCodec<ByteBuf, ArchiveWritePayload> =
            Identifier.STREAM_CODEC.map(::ArchiveWritePayload, ArchiveWritePayload::word)
    }
}

/** Carrying out a withdrawal, re-checked against the archive whatever the screen believed. */
object ArchiveCommands {

    /**
     * Writes a page into the archive, re-checking everything the screen believed: that the word is known,
     * that a desk is in range, and that all of the ink and the sheet can be paid. Nothing is spent unless
     * the page is filed.
     */
    fun write(player: ServerPlayer, payload: ArchiveWritePayload) {
        val menu = player.containerMenu as? ArchiveMenu ?: return
        val archive = menu.archiveOf() ?: return
        val level = archive.level ?: return
        val server = level.server ?: return
        if (!player.learnedWords.knows(payload.word)) return
        val reach = WritersDesk.of(server).radius
        val desk = ArchiveWriting.closestDesk(level, archive.blockPos, reach) ?: return
        val cost = ArchiveWriting.costOf(ArchiveWriting.priceOf(player, payload.word))
        if (!desk.pay(cost)) return
        archive.file(payload.word, 1)
    }

    fun withdraw(player: ServerPlayer, payload: ArchiveWithdrawPayload) {
        val menu = player.containerMenu as? ArchiveMenu ?: return
        val archive = menu.archiveOf() ?: return
        val vocabulary = Vocabulary.of(player.level().server)
        // A structural page has no rarity to stamp, and is made as every other structural page is.
        val word = vocabulary.word(payload.word.toString()) ?: vocabulary.word(payload.word.path)
        val page = if (word != null) PageItem.writtenWith(word, vocabulary) else PageItem.writtenWith(payload.word)
        val wanted = if (payload.wholeStack) page.maxStackSize else 1
        val count = wanted.coerceAtMost(archive.pages.count(payload.word))
        if (count <= 0 || !archive.take(payload.word, count)) return
        val taken = page.copyWithCount(count)
        player.inventory.placeItemBackInInventory(taken, Prediction.SERVER_ONLY)
    }
}

package co.voik.agesandtheart.desk

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

/** Carrying out a withdrawal, re-checked against the archive whatever the screen believed. */
object ArchiveCommands {
    fun withdraw(player: ServerPlayer, payload: ArchiveWithdrawPayload) {
        val menu = player.containerMenu as? ArchiveMenu ?: return
        val archive = menu.archiveOf() ?: return
        val page = PageItem.writtenWith(payload.word)
        val wanted = if (payload.wholeStack) page.maxStackSize else 1
        val count = wanted.coerceAtMost(archive.pages.count(payload.word))
        if (count <= 0 || !archive.take(payload.word, count)) return
        val taken = page.copyWithCount(count)
        if (!player.inventory.add(taken)) player.drop(taken, false)
    }
}

package co.voik.agesandtheart.book

import co.voik.agesandtheart.Constants
import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack

/**
 * One book on a finder's wall, as its list shows it: where it is, what it is called and searched by, and the book itself
 * as a client may see it, for the tooltip it would have in an inventory.
 */
data class FinderBook(val shelf: BlockPos, val slot: Int, val name: String, val book: ItemStack) {
    companion object {
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, FinderBook> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, FinderBook::shelf,
            ByteBufCodecs.VAR_INT, FinderBook::slot,
            ByteBufCodecs.STRING_UTF8, FinderBook::name,
            ItemStack.STREAM_CODEC, FinderBook::book,
            ::FinderBook,
        )
    }
}

/** Every book on the wall of the finder at [finder], sent to the player who used it, whose screen opens on it. */
data class BookFinderListPayload(val finder: BlockPos, val books: List<FinderBook>) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<BookFinderListPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<BookFinderListPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "book_finder_list"))

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, BookFinderListPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, BookFinderListPayload::finder,
            FinderBook.STREAM_CODEC.apply(ByteBufCodecs.list()), BookFinderListPayload::books,
            ::BookFinderListPayload,
        )
    }
}

/** A player's choice from a finder's list: fetch the book in [slot] of [shelf]. Checked on the server. */
data class BookFinderFetchPayload(val finder: BlockPos, val shelf: BlockPos, val slot: Int) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<BookFinderFetchPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<BookFinderFetchPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "book_finder_fetch"))

        val STREAM_CODEC: StreamCodec<ByteBuf, BookFinderFetchPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, BookFinderFetchPayload::finder,
            BlockPos.STREAM_CODEC, BookFinderFetchPayload::shelf,
            ByteBufCodecs.VAR_INT, BookFinderFetchPayload::slot,
            ::BookFinderFetchPayload,
        )

        fun handle(player: ServerPlayer, payload: BookFinderFetchPayload) = BookFinders.handleFetch(player, payload)
    }
}

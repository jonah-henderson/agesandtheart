package co.voik.agesandtheart.book

import co.voik.agesandtheart.Constants
import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer

/** One book on a finder's wall, as its list shows it: where it is, and what it is called. */
data class FinderBook(val shelf: BlockPos, val slot: Int, val name: String) {
    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, FinderBook> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, FinderBook::shelf,
            ByteBufCodecs.VAR_INT, FinderBook::slot,
            ByteBufCodecs.STRING_UTF8, FinderBook::name,
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

        val STREAM_CODEC: StreamCodec<ByteBuf, BookFinderListPayload> = StreamCodec.composite(
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

/** What a reader asks of the book a finder holds open, from its screen. */
data class BookFinderAskPayload(val finder: BlockPos, val asked: FinderAsked) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<BookFinderAskPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<BookFinderAskPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "book_finder_ask"))

        private val ASKED: StreamCodec<ByteBuf, FinderAsked> =
            ByteBufCodecs.VAR_INT.map({ FinderAsked.entries[it] }, FinderAsked::ordinal)

        val STREAM_CODEC: StreamCodec<ByteBuf, BookFinderAskPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, BookFinderAskPayload::finder,
            ASKED, BookFinderAskPayload::asked,
            ::BookFinderAskPayload,
        )

        fun handle(player: ServerPlayer, payload: BookFinderAskPayload) = BookFinders.handleAsked(player, payload)
    }
}

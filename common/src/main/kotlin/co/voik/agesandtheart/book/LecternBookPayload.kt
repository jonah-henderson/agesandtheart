package co.voik.agesandtheart.book

import co.voik.agesandtheart.Constants
import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer

/** A reader asking, from the screen of the book open on the lectern at [pos], to take it back or to close it. */
data class LecternBookPayload(val pos: BlockPos, val asked: LecternBooks.Asked) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<LecternBookPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<LecternBookPayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "lectern_book"),
        )

        private val ASKED: StreamCodec<ByteBuf, LecternBooks.Asked> =
            ByteBufCodecs.VAR_INT.map({ LecternBooks.Asked.entries[it] }, LecternBooks.Asked::ordinal)

        val STREAM_CODEC: StreamCodec<ByteBuf, LecternBookPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, LecternBookPayload::pos,
            ASKED, LecternBookPayload::asked,
            ::LecternBookPayload,
        )

        fun handle(player: ServerPlayer, payload: LecternBookPayload) = LecternBooks.answer(player, payload.pos, payload.asked)
    }
}

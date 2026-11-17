package co.voik.agesandtheart.imaging

import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/**
 * One piece of a picture going up to the server: a client may send no more than 32 KiB in one payload, so a picture
 * goes in as many of these as it takes, in order, each saying how many there are. The field of view rides along so the
 * server can draw the same view itself where pictures are not shared.
 */
data class PicturePiece(val index: Int, val count: Int, val fieldOfView: Float, val bytes: ByteArray) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<PicturePiece> = TYPE

    companion object {
        /** Under the 32767 bytes a client's payload may carry, with room for the rest of the piece. */
        const val MOST_BYTES = 30_000

        val TYPE: CustomPacketPayload.Type<PicturePiece> = CustomPacketPayload.Type(Imaging.id("picture_piece"))

        val STREAM_CODEC: StreamCodec<ByteBuf, PicturePiece> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PicturePiece::index,
            ByteBufCodecs.VAR_INT, PicturePiece::count,
            ByteBufCodecs.FLOAT, PicturePiece::fieldOfView,
            ByteBufCodecs.byteArray(MOST_BYTES), PicturePiece::bytes,
            ::PicturePiece,
        )
    }

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
}

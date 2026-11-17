package co.voik.agesandtheart.imaging

import io.netty.buffer.ByteBuf
import net.minecraft.core.UUIDUtil
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Util
import java.util.UUID

/** A client asking for one picture at one size, the first time it needs it there. */
data class PictureWanted(val id: UUID, val size: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<PictureWanted> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<PictureWanted> = CustomPacketPayload.Type(Imaging.id("picture_wanted"))
        val STREAM_CODEC: StreamCodec<ByteBuf, PictureWanted> = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, PictureWanted::id,
            ByteBufCodecs.VAR_INT, PictureWanted::size,
            ::PictureWanted,
        )
    }
}

/** The picture asked for, or no bytes where it has not been developed yet, or never will be. */
class PictureSent(val id: UUID, val size: Int, val bytes: ByteArray) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<PictureSent> = TYPE

    companion object {
        /** Under the mebibyte a server's payload may carry; the largest size is written well under it. */
        private const val MOST_BYTES = 1_000_000

        val TYPE: CustomPacketPayload.Type<PictureSent> = CustomPacketPayload.Type(Imaging.id("picture_sent"))
        val STREAM_CODEC: StreamCodec<ByteBuf, PictureSent> = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, PictureSent::id,
            ByteBufCodecs.VAR_INT, PictureSent::size,
            ByteBufCodecs.byteArray(MOST_BYTES), PictureSent::bytes,
            ::PictureSent,
        )
    }
}

/** Answering [PictureWanted]: the file read off the server thread, and the answer sent once it is. */
object PictureFetch {

    /** As many asks as a client has any business making in a second, a room of pictures coming into view at once. */
    private const val MOST_ASKS_A_SECOND = 40
    private const val TICKS_A_SECOND = 20

    private val asked = HashMap<UUID, IntArray>()

    fun wanted(player: ServerPlayer, wanted: PictureWanted) {
        val size = PictureSizes.Size.entries.getOrNull(wanted.size) ?: return
        if (!allow(player)) return
        val server = player.level().server
        Util.ioPool().execute {
            val bytes = PictureStore.read(server, wanted.id, size) ?: ByteArray(0)
            server.execute {
                if (!player.hasDisconnected()) player.connection.send(ClientboundCustomPayloadPacket(PictureSent(wanted.id, wanted.size, bytes)))
            }
        }
    }

    private fun allow(player: ServerPlayer): Boolean {
        val second = player.level().server.tickCount / TICKS_A_SECOND
        val count = asked.getOrPut(player.uuid) { intArrayOf(second, 0) }
        if (count[0] != second) {
            count[0] = second
            count[1] = 0
        }
        count[1]++
        return count[1] <= MOST_ASKS_A_SECOND
    }
}

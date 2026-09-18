package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.Constants
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/**
 * That the rain in the Age you are standing in is a deluge's, and how heavy — on its way to the client.
 *
 * **Sent rather than derived**, for the reason [BlizzardPayload] is: whether an Age has a deluge is a fact
 * about its recipe, and how heavy folds in an instability the client is never told. Only *whether it is
 * raining* crosses on its own, and that cannot tell a deluge from a shower.
 *
 * A payload from [noneIn] is how an Age says it has no deluge, so leaving a drowning Age for an ordinary
 * one does not carry the downpour with you.
 */
data class DelugePayload(
    /** Which Age this is about, so a message in flight across a link is refused by the next world. */
    val age: Identifier,
    /** How heavy the rain is, nought for an ordinary deluge to one for a bought-out one; negative for none. */
    val heaviness: Double,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<DelugePayload> = TYPE

    val pouring: Boolean get() = heaviness >= ORDINARY

    companion object {
        val TYPE: CustomPacketPayload.Type<DelugePayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "deluge"),
        )

        val STREAM_CODEC: StreamCodec<ByteBuf, DelugePayload> = StreamCodec.composite(
            Identifier.STREAM_CODEC,
            DelugePayload::age,
            ByteBufCodecs.DOUBLE,
            DelugePayload::heaviness,
            ::DelugePayload,
        )

        private const val ORDINARY = 0.0
        private const val NONE = -1.0

        /** What an Age with no deluge says, which is most of them. */
        fun noneIn(age: Identifier) = DelugePayload(age, NONE)
    }
}

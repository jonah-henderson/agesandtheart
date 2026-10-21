package co.voik.agesandtheart.content

import co.voik.agesandtheart.Constants
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer

/** The page a player scrolled to in the notebook in [slot] of their open menu, or [NONE]. Vanilla's bundle selection's own. */
data class NotebookSelectPayload(val slot: Int, val selected: Int) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<NotebookSelectPayload> = TYPE

    companion object {
        const val NONE = -1

        val TYPE: CustomPacketPayload.Type<NotebookSelectPayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "notebook_select"),
        )

        val STREAM_CODEC: StreamCodec<ByteBuf, NotebookSelectPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, NotebookSelectPayload::slot,
            ByteBufCodecs.VAR_INT, NotebookSelectPayload::selected,
            ::NotebookSelectPayload,
        )

        fun handle(player: ServerPlayer, payload: NotebookSelectPayload) {
            val slot = player.containerMenu.slots.getOrNull(payload.slot) ?: return
            NotebookItem.select(slot.item, payload.selected)
        }
    }
}

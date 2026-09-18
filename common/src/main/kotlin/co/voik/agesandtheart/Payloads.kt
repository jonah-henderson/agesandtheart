package co.voik.agesandtheart

import co.voik.agesandtheart.age.phenomena.BlizzardPayload
import co.voik.agesandtheart.age.phenomena.DelugePayload
import co.voik.agesandtheart.age.word.LearnedWordsPayload
import co.voik.agesandtheart.age.word.LexiconPayload
import co.voik.agesandtheart.book.LinkRequest
import co.voik.agesandtheart.book.Linking
import co.voik.agesandtheart.book.panel.PanelChunkPayload
import co.voik.agesandtheart.book.panel.PanelChunksWanted
import co.voik.agesandtheart.book.panel.PanelCloseRequest
import co.voik.agesandtheart.book.panel.PanelLevelPayload
import co.voik.agesandtheart.book.panel.PanelOpenRequest
import co.voik.agesandtheart.book.panel.PanelViews
import co.voik.agesandtheart.desk.DeskCommandPayload
import co.voik.agesandtheart.desk.DeskCommands
import co.voik.agesandtheart.desk.DeskNoticePayload
import co.voik.agesandtheart.desk.DeskPricePayload
import co.voik.agesandtheart.desk.DeskSyncPayload
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import co.voik.agesandtheart.worldgen.fissure.FallResumedPayload
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer

/**
 * Every payload the mod sends, in the order each loader registers them, with the server's handler for each
 * one a client sends.
 *
 * A clientbound payload's handler is client code, so it is listed in `client/ClientPayloads` instead, where a
 * dedicated server never loads it. A change to a codec or to what a handler means needs NeoForge's
 * `PAYLOAD_VERSION` bumped.
 */
object Payloads {
    /** One payload's type and codec, and which way it travels. */
    sealed class Route<T : CustomPacketPayload>(
        val type: CustomPacketPayload.Type<T>,
        val codec: StreamCodec<in RegistryFriendlyByteBuf, T>,
    )

    /** Sent by the server, and received by a client's handler in `ClientPayloads`. */
    class Clientbound<T : CustomPacketPayload>(
        type: CustomPacketPayload.Type<T>,
        codec: StreamCodec<in RegistryFriendlyByteBuf, T>,
    ) : Route<T>(type, codec)

    /** Sent by a client, and handled on the server thread by [handle]. */
    class Serverbound<T : CustomPacketPayload>(
        type: CustomPacketPayload.Type<T>,
        codec: StreamCodec<in RegistryFriendlyByteBuf, T>,
        val handle: (ServerPlayer, T) -> Unit,
    ) : Route<T>(type, codec)

    val ROUTES: List<Route<*>> = listOf(
        Clientbound(LexiconPayload.TYPE, LexiconPayload.STREAM_CODEC),
        Clientbound(BlizzardPayload.TYPE, BlizzardPayload.STREAM_CODEC),
        Clientbound(DelugePayload.TYPE, DelugePayload.STREAM_CODEC),
        Clientbound(LearnedWordsPayload.TYPE, LearnedWordsPayload.STREAM_CODEC),
        Clientbound(DeskSyncPayload.TYPE, DeskSyncPayload.STREAM_CODEC),
        Clientbound(DeskPricePayload.TYPE, DeskPricePayload.STREAM_CODEC),
        Clientbound(DeskNoticePayload.TYPE, DeskNoticePayload.STREAM_CODEC),
        // A fall through a tear that a disconnection interrupted, taken up again.
        Clientbound(FallResumedPayload.TYPE, FallResumedPayload.STREAM_CODEC),
        // The desk's instructions, re-checked server-side whatever the screen believed.
        Serverbound(DeskCommandPayload.TYPE, DeskCommandPayload.STREAM_CODEC, DeskCommands::handle),
        Serverbound(LinkRequest.TYPE, LinkRequest.STREAM_CODEC, Linking::handle),
        // The linking panel (design 7.8.1). The chunk payload is the only one in the mod keyed to a registry
        // buffer, carrying vanilla's own chunk and light data straight through.
        Clientbound(PanelLevelPayload.TYPE, PanelLevelPayload.STREAM_CODEC),
        Clientbound(PanelChunkPayload.TYPE, PanelChunkPayload.STREAM_CODEC),
        Serverbound(PanelOpenRequest.TYPE, PanelOpenRequest.STREAM_CODEC) { player, request ->
            PanelViews.open(player.level().server, player, request.book)
        },
        Serverbound(PanelCloseRequest.TYPE, PanelCloseRequest.STREAM_CODEC) { player, _ ->
            PanelViews.close(player.level().server, player)
        },
        Serverbound(PanelChunksWanted.TYPE, PanelChunksWanted.STREAM_CODEC) { player, wanted ->
            PanelViews.resend(player.level().server, player, wanted.positions)
        },
    )
}

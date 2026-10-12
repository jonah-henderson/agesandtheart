package co.voik.agesandtheart.book

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.location
import co.voik.agesandtheart.platform.Services
import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.LecternBlockEntity
import java.util.UUID

/**
 * Links waiting for the ground they arrive on.
 *
 * Arriving needs the arrival's chunk whole, and asking for one that is not yet generated built it on the server
 * thread: seconds on a cold Age, with every player on the server stopped and the linker looking at nothing. So
 * a link whose arrival is not ready holds a ticket on it and waits a tick at a time while the chunk is built
 * elsewhere, and goes the tick it is ready. One that is ready goes at once, as before.
 *
 * Everything is asked again when the wait ends — the book in the hand, the book on the lectern, the refusals
 * — because the world may have moved under a link that waited.
 */
object LinksUnderway {

    /** Where a link leaves from, which is what has to still be true when it goes. */
    sealed interface Departure {
        /** A book in [hand], which must still be [book] when the link goes. */
        data class FromHand(val hand: InteractionHand, val book: ItemStack) : Departure

        /** The book lying open on the lectern at [pos] in [dimension]. */
        data class FromLectern(val dimension: ResourceKey<Level>, val pos: BlockPos) : Departure
    }

    private class Waiting(val departure: Departure, val journey: Linking.Journey, val since: Int)

    private val waiting = mutableMapOf<UUID, Waiting>()

    /** Starts [player]'s link from [departure]: at once where its arrival is ready, and otherwise once it is. */
    fun begin(player: ServerPlayer, departure: Departure) {
        if (player.uuid in waiting) return
        val book = bookAt(player, departure) ?: return endedWithoutGoing(player)
        val journey = Linking.journeyFor(player, player.level(), book) ?: return
        if (isReady(journey)) {
            go(player, departure)
            return
        }
        Constants.LOG.debug("{} is linking to {}, waiting for its arrival", player.gameProfile.name, journey.into.dimension())
        hold(journey)
        waiting[player.uuid] = Waiting(departure, journey, player.level().server.tickCount)
    }

    /** Sends on every link whose arrival has become ready, and gives up on any that waited too long. */
    fun tick(server: MinecraftServer) {
        if (waiting.isEmpty()) return
        for ((id, link) in waiting.toList()) {
            val player = server.playerList.getPlayer(id)
            val stillThere = player != null && player.isAlive
            val waitedTooLong = server.tickCount - link.since > MOST_TICKS_WAITED
            when {
                !stillThere -> waiting.remove(id)
                waitedTooLong -> {
                    waiting.remove(id)
                    Constants.LOG.warn("A link to {} waited too long for its arrival, and was given up", link.journey.into.dimension())
                    Linking.refuse(player, "failed")
                }
                isReady(link.journey) -> {
                    waiting.remove(id)
                    go(player, link.departure)
                }
                else -> hold(link.journey)
            }
        }
    }

    /** Called when a client stops waiting — escape on the transit, or a disconnect. */
    fun cancel(player: ServerPlayer) {
        waiting.remove(player.uuid)
    }

    /** An integrated server stopping, whose players and levels the next world will not have. */
    fun serverStopped() = waiting.clear()

    /** Tells [player]'s client a link it may be showing is not going to happen. */
    fun endedWithoutGoing(player: ServerPlayer) {
        waiting.remove(player.uuid)
        Services.NETWORK.sendToPlayer(player, LinkEnded)
    }

    private fun go(player: ServerPlayer, departure: Departure) {
        val level = player.level()
        when (departure) {
            is Departure.FromHand -> {
                val held = player.getItemInHand(departure.hand)
                val sameBook = ItemStack.isSameItemSameComponents(held, departure.book)
                if (!sameBook || !Linking.link(player, level, held, departure.hand)) endedWithoutGoing(player)
            }
            is Departure.FromLectern -> {
                val lectern = lecternAt(player, departure)
                val went = lectern != null && Linking.go(player, level, lectern.book)
                if (lectern != null && went) lectern.setChanged()
                if (!went) endedWithoutGoing(player)
            }
        }
    }

    private fun bookAt(player: ServerPlayer, departure: Departure): ItemStack? = when (departure) {
        is Departure.FromHand -> player.getItemInHand(departure.hand).takeIf(LecternBooks::isOurs)
        is Departure.FromLectern -> lecternAt(player, departure)?.book
    }

    /** The lectern a link leaves from, while [player] is still in its world and it still holds a book of ours. */
    private fun lecternAt(player: ServerPlayer, departure: Departure.FromLectern): LecternBlockEntity? {
        if (player.level().dimension() != departure.dimension) return null
        val lectern = player.level().getBlockEntity(departure.pos) as? LecternBlockEntity ?: return null
        return lectern.takeIf { LecternBooks.isOurs(it.book) }
    }

    /** Whether the arrival's chunk is whole and loaded, so going would build nothing on this thread. */
    private fun isReady(journey: Linking.Journey): Boolean =
        journey.into.chunkSource.getChunkNow(journey.arrivingAt.x, journey.arrivingAt.z) != null

    /**
     * Keeps the arrival loading. Renewed every tick of the wait and left to run out after it, which carries the
     * chunk across the tick where the arriving player's own tickets take it over.
     */
    private fun hold(journey: Linking.Journey) =
        journey.into.chunkSource.addTicketWithRadius(AgeContent.LINK_TICKET, journey.arrivingAt, ARRIVAL_RADIUS_CHUNKS)

    /** The arrival and the chunks round it, so the visitor does not land at the edge of the loaded world. */
    private const val ARRIVAL_RADIUS_CHUNKS = 1

    /** A minute: a cold Age's arrival takes seconds, and a link still waiting after this has gone wrong. */
    private const val MOST_TICKS_WAITED = 20 * 60
}

/** A link that a client may be showing as under way, ended without going. */
object LinkEnded : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<LinkEnded> = TYPE

    /** An `object` for `StreamCodec.unit`, as `PanelCloseRequest` is. */
    val TYPE: CustomPacketPayload.Type<LinkEnded> = CustomPacketPayload.Type("link_ended".location())

    val STREAM_CODEC: StreamCodec<ByteBuf, LinkEnded> = StreamCodec.unit(LinkEnded)
}

/** A client giving up on a link it asked for. */
object LinkCancelRequest : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<LinkCancelRequest> = TYPE

    val TYPE: CustomPacketPayload.Type<LinkCancelRequest> = CustomPacketPayload.Type("link_cancel".location())

    val STREAM_CODEC: StreamCodec<ByteBuf, LinkCancelRequest> = StreamCodec.unit(LinkCancelRequest)
}

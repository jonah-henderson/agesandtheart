package co.voik.agesandtheart.book.panel

import co.voik.agesandtheart.AgeConfig
import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.PanelView
import co.voik.agesandtheart.book.BookBeingRead
import co.voik.agesandtheart.platform.Services
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Util
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.status.ChunkStatus
import java.util.UUID

/**
 * Map panels (`AgeConfig.panelMode` MAP): each book's map read off its Age and sent, as often as books ask.
 *
 * Nothing is held. A chunk is read on the server thread the moment it is ready and not kept loaded by anything of
 * ours, so a map costs one read of the ring and the server has no notion of which maps a client is showing.
 */
object PanelMapViews {

    /** When each player last asked after each book, so one book cannot be asked after faster than [TICKS_BETWEEN_ASKS]. */
    private val askedOnTick = mutableMapOf<UUID, MutableMap<BookBeingRead, Long>>()

    /** A second: a client asks once when a book comes into reach, and again only after it has heard nothing. */
    private const val TICKS_BETWEEN_ASKS = 20L

    fun request(server: MinecraftServer, player: ServerPlayer, request: PanelMapRequest) {
        if (AgeConfig.panelMode.get() != PanelView.MAP) return
        if (!isPaced(server, player, request.book)) return
        val destination = PanelViews.destinationFor(server, player, request.book) ?: return
        val level = destination.level
        val around = destination.around
        val ring = PanelRing.shownAround(PanelRing.centreOf(around))
        val wanted = if (request.positions.isEmpty()) ring else ring.filter { it in request.positions }
        if (request.positions.isEmpty()) {
            Constants.LOG.info(
                "Panel map of {} for {}'s {}, {} chunks around {}",
                level.dimension().identifier(), player.name.string, request.book, ring.size, around,
            )
            Services.NETWORK.sendToPlayer(player, PanelMapPayload(request.book, request.ask, around, ring.size))
        }
        // Asked from off the server thread, as a live ring is (`PanelViews.openLive`): there `getChunkFuture`
        // generates the chunk before it returns.
        Util.backgroundExecutor().execute {
            wanted.forEach { send(server, player, level, around, request, it) }
        }
    }

    /** Forgets [player]'s pacing, when they leave. */
    fun forget(player: ServerPlayer) {
        askedOnTick.remove(player.uuid)
    }

    private fun isPaced(server: MinecraftServer, player: ServerPlayer, book: BookBeingRead): Boolean {
        val now = server.overworld().gameTime
        val asked = askedOnTick.getOrPut(player.uuid) { mutableMapOf() }
        val previously = asked[book]
        if (previously != null && now - previously < TICKS_BETWEEN_ASKS) return false
        asked[book] = now
        return true
    }

    private fun send(
        server: MinecraftServer,
        player: ServerPlayer,
        level: ServerLevel,
        around: BlockPos,
        request: PanelMapRequest,
        position: ChunkPos,
    ) {
        level.chunkSource.getChunkFuture(position.x, position.z, ChunkStatus.FULL, true)
            .thenAcceptAsync({ result ->
                val chunk = result.orElse(null) as? LevelChunk ?: return@thenAcceptAsync
                if (player.hasDisconnected()) return@thenAcceptAsync
                Services.NETWORK.sendToPlayer(player, PanelMapColumns.of(level, chunk, around, request.book, request.ask))
            }, server)
    }
}

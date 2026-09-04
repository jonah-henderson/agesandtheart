package co.voik.agesandtheart.book.panel

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.Timing
import co.voik.agesandtheart.age.Ages
import co.voik.agesandtheart.book.BookAge
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.location
import co.voik.agesandtheart.platform.Services
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.TicketType
import net.minecraft.world.InteractionHand
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.BiomeManager
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.status.ChunkStatus
import java.util.UUID

/**
 * Who is looking at what, and the ring of chunks that costs.
 *
 * One panel per player, held only while a book is open (design §7.8.1). Everything it loads it releases:
 * opening a second panel closes the first, closing the book closes it, and leaving the server closes it.
 */
object PanelViews {

    /** Registered at init by each loader — see [AgeContent.PANEL_TICKET] for why it cannot live here. */
    private val PANEL_TICKET: TicketType get() = AgeContent.PANEL_TICKET

    /** One player's open panel: what it is looking at, and everything owed to it. */
    private class Watch(
        val dimension: ResourceKey<Level>,
        val centre: ChunkPos,
        /** When the ring was first asked for, so the whole stream can be timed end to end. */
        val ringBeganAt: Long,
    ) {
        /**
         * Which of the ring's chunks have been sent, so the last one can say how long the ring took.
         *
         * Positions rather than a counter, because a re-sent chunk would decrement one twice and step the
         * total past the value that says the ring is whole.
         */
        val sent = mutableSetOf<Long>()
    }

    private val watching = mutableMapOf<UUID, Watch>()

    /**
     * When each player last opened a panel, outliving the view it opened.
     *
     * Opening generates up to [PanelRing.COUNT] chunks and a client decides when it happens. It cannot
     * live on [Watch]: closing the book removes the watch, and a limit a close resets is not a limit.
     */
    private val openedOnTick = mutableMapOf<UUID, Long>()

    /** And when each last asked again, for the same reason and by the same guard. */
    private val chasedOnTick = mutableMapOf<UUID, Long>()

    private const val TICKS_BETWEEN_PANELS = 10L

    /** Re-requests are cheaper than an open but still ask the chunk source for work. */
    private const val TICKS_BETWEEN_CHASES = 20L

    /**
     * Opens a panel onto whatever bound book [player] is holding in [hand], streaming its arrival ring.
     *
     * Resolves the book through `BookAge`, the same call linking makes, so the world shown and the world
     * you arrive in are one and whichever asks first is the one that mints it.
     */
    fun open(server: MinecraftServer, player: ServerPlayer, hand: InteractionHand) {
        if (!allow(server, player, openedOnTick, TICKS_BETWEEN_PANELS)) return
        close(server, player)

        val openedAt = System.nanoTime()
        val stack = Timing.of("server: read the held book") { player.getItemInHand(hand) }
        if (stack.item !== AgeContent.DESCRIPTIVE_BOOK) {
            Constants.LOG.info("Panel refused: {} is holding {}, which is not a book", player.name.string, stack.item)
            return
        }
        val level = Timing.of("server: roll and open the Age") { BookAge.of(server, stack) }
        if (level == null) {
            Constants.LOG.warn("Panel wanted the Age of a book in {}'s hand and it would not open", player.name.string)
            return
        }

        val around = Timing.of("server: find the arrival") { arrivalIn(level) }
        val centre = PanelRing.centreOf(around)
        watching[player.uuid] = Watch(level.dimension(), centre, System.nanoTime())
        Timing.of("server: hold the ring") { hold(level, centre) }

        Constants.LOG.info(
            "Panel opened onto {} for {}, streaming {} chunks around {}",
            level.dimension().identifier(), player.name.string, PanelRing.COUNT, around,
        )
        Timing.of("server: send the level payload") {
            Services.NETWORK.sendToPlayer(
                player,
                PanelLevelPayload(
                    dimension = level.dimension(),
                    dimensionType = dimensionTypeIdOf(level),
                    around = around,
                    biomeZoomSeed = BiomeManager.obfuscateSeed(level.seed),
                    seaLevel = level.seaLevel,
                    chunksComing = PanelRing.COUNT,
                    gameTime = level.gameTime,
                ),
            )
        }
        // Timed apart from the ring itself: scheduling is not free, and its cost is not the wait.
        Timing.of("server: ask for all the ring's chunks") {
            PanelRing.around(centre).forEach { send(server, player, level, centre, it) }
        }
        Timing.record("server: the open handler, end to end", System.nanoTime() - openedAt)
    }

    /**
     * Sends whatever of the ring the client says never reached it (see [PanelChunksWanted]).
     *
     * Trusts the list for what to resend and nothing else: the positions are intersected with the ring
     * actually being held, so a request cannot load a chunk there is no ticket for or reach another Age.
     */
    fun resend(server: MinecraftServer, player: ServerPlayer, positions: List<ChunkPos>) {
        val watch = watching[player.uuid] ?: return
        val level = server.getLevel(watch.dimension) ?: return

        // Filtered before the clock is stamped, so a request naming nothing in the ring cannot spend the
        // window that the next real one needs.
        val ring = PanelRing.around(watch.centre).toSet()
        val wanted = positions.filterTo(LinkedHashSet()) { it in ring }
        if (wanted.isEmpty()) return
        if (!allow(server, player, chasedOnTick, TICKS_BETWEEN_CHASES)) return

        Constants.LOG.info(
            "Panel: {} asked again for {} of {}'s chunks",
            player.name.string, wanted.size, watch.dimension.identifier(),
        )
        wanted.forEach { send(server, player, level, watch.centre, it) }
    }

    /** Releases whatever [player] was looking at, if anything. Safe to call when there is nothing. */
    fun close(server: MinecraftServer, player: ServerPlayer) {
        val watch = watching.remove(player.uuid) ?: return
        val level = server.getLevel(watch.dimension) ?: return
        release(level, watch.centre)
    }

    /** Called when a player leaves, since a client that crashed with a book open never says so. */
    fun forget(server: MinecraftServer, player: ServerPlayer) {
        close(server, player)
        openedOnTick.remove(player.uuid)
        chasedOnTick.remove(player.uuid)
    }

    /**
     * Whether [player] may ask for something again yet, stamping the clock when they may.
     *
     * Shared by opening and re-requesting because they are the same guard against the same thing: both are
     * client-initiated, both make the chunk source work, and neither is anything a person can do quickly.
     */
    private fun allow(
        server: MinecraftServer,
        player: ServerPlayer,
        clock: MutableMap<UUID, Long>,
        gap: Long,
    ): Boolean {
        val now = server.overworld().gameTime
        val previously = clock[player.uuid]
        if (previously != null && now - previously < gap) return false
        clock[player.uuid] = now
        return true
    }

    /**
     * The point a visitor would arrive at, which is what the orbit is centred on.
     *
     * Deliberately the same call the link makes, so a panel cannot frame somewhere other than where it
     * puts you — including when that is underwater or buried.
     */
    private fun arrivalIn(level: ServerLevel): BlockPos = Ages.arrivalIn(level)

    private fun dimensionTypeIdOf(level: ServerLevel) =
        level.registryAccess()
            .lookupOrThrow(net.minecraft.core.registries.Registries.DIMENSION_TYPE)
            .getKey(level.dimensionType())
            ?: net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD.identifier()

    private fun hold(level: ServerLevel, centre: ChunkPos) =
        level.chunkSource.addTicketWithRadius(PANEL_TICKET, centre, PanelRing.HELD_RADIUS_CHUNKS)

    private fun release(level: ServerLevel, centre: ChunkPos) =
        level.chunkSource.removeTicketWithRadius(PANEL_TICKET, centre, PanelRing.HELD_RADIUS_CHUNKS)

    /**
     * One chunk of the ring, sent when it is ready.
     *
     * A future rather than `getChunk`, which blocks until the chunk is generated and would stop the server
     * for the whole ring. The watch is re-checked when the chunk lands, since the book may have closed.
     */
    private fun send(
        server: MinecraftServer,
        player: ServerPlayer,
        level: ServerLevel,
        centre: ChunkPos,
        position: ChunkPos,
    ) {
        val asked = System.nanoTime()
        level.chunkSource.getChunkFuture(position.x, position.z, ChunkStatus.FULL, true)
            .thenAcceptAsync({ result ->
                Timing.record("server: generate one chunk", System.nanoTime() - asked)
                val chunk = result.orElse(null) as? LevelChunk
                if (chunk == null) {
                    // Said rather than dropped: a ring that finishes a chunk short is otherwise silent.
                    Constants.LOG.warn(
                        "Panel: {},{} came back from the chunk source with no chunk, so it goes unsent",
                        position.x, position.z,
                    )
                    return@thenAcceptAsync
                }
                // The dimension as well as the centre: arrivals cluster near the origin, so two Ages
                // sharing a centre chunk is ordinary, and a stale future would stream one Age's terrain
                // into the other's panel — the payload carries only x and z.
                val watch = watching[player.uuid]
                    ?.takeIf { it.centre == centre && it.dimension == level.dimension() }
                    ?: return@thenAcceptAsync
                Timing.of("server: serialise and send one chunk") {
                    Services.NETWORK.sendToPlayer(
                        player,
                        PanelChunkPayload(
                            x = position.x,
                            z = position.z,
                            chunk = ClientboundLevelChunkPacketData(chunk),
                            light = ClientboundLightUpdatePacketData(position, level.lightEngine, null, null),
                        ),
                    )
                }
                if (watch.sent.add(ChunkPos.pack(position.x, position.z)) && watch.sent.size == PanelRing.COUNT) {
                    val ring = System.nanoTime() - watch.ringBeganAt
                    Timing.record("server: the whole ring, first ask to last send", ring)
                }
            }, server)
    }
}

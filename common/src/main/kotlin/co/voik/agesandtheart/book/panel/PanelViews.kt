package co.voik.agesandtheart.book.panel

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.Ages
import co.voik.agesandtheart.book.BookAge
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.location
import co.voik.agesandtheart.platform.Services
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.TicketType
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.biome.BiomeManager
import net.minecraft.world.InteractionHand
import net.minecraft.world.level.Level
import net.minecraft.resources.ResourceKey
import java.util.UUID

/**
 * Who is looking at what, and the ring of chunks that costs.
 *
 * **One panel per player and never more.** A book renders only while it is open (§7.8.1), so this holds at
 * most one view each — which is what turns *a loaded dimension per book a player carries* into *one,
 * briefly, while a screen is up*, and is the ruling that makes the whole feature ordinary rather than
 * extravagant.
 *
 * **Everything it loads, it releases**: opening a second panel closes the first, closing the book closes
 * it, and leaving the server closes it. A ring that outlived its viewer would hold an Age open forever,
 * which is the failure this class exists to make impossible rather than unlikely.
 */
object PanelViews {

    /** Registered at init by each loader — see [AgeContent.PANEL_TICKET] for why it cannot live here. */
    private val PANEL_TICKET: TicketType get() = AgeContent.PANEL_TICKET

    private data class View(val dimension: ResourceKey<Level>, val centre: ChunkPos)

    private val watching = mutableMapOf<UUID, View>()

    /**
     * When each player last opened a panel, so one cannot be asked for faster than it can be served.
     *
     * **Because opening one is expensive and a client decides when it happens.** Streaming the ring
     * generates up to [PanelProtocol.RING_CHUNKS] chunks, and on a freshly written Age none of them exists
     * yet — so a client sending open requests in a loop would keep the server generating terrain and
     * nothing else. A book is opened by hand; twice a second is far more than a person can ask for and far
     * less than a loop would.
     */
    private val lastOpened = mutableMapOf<UUID, Long>()

    private const val TICKS_BETWEEN_PANELS = 10L

    /**
     * Opens a panel onto whatever bound book [player] is holding in [hand], streaming its arrival ring.
     *
     * **Resolves the book through `BookAge`, which is the same call linking makes** — so the world shown
     * and the world you arrive in are one, and whichever asks first is the one that mints it. A bound
     * book's Age is decided by its words and its seed; making it here is bringing it into being, not
     * choosing it, so nothing about §7.5 turns on which of the two asked.
     *
     * Refuses anything that is not a descriptive book, which is the only thing that carries an Age.
     */
    fun open(server: MinecraftServer, player: ServerPlayer, hand: InteractionHand) {
        val now = server.overworld().gameTime
        val previously = lastOpened[player.uuid]
        if (previously != null && now - previously < TICKS_BETWEEN_PANELS) return
        lastOpened[player.uuid] = now
        close(server, player)

        val stack = player.getItemInHand(hand)
        if (stack.item !== AgeContent.DESCRIPTIVE_BOOK) {
            Constants.LOG.info("Panel refused: {} is holding {}, which is not a book", player.name.string, stack.item)
            return
        }
        val level = BookAge.of(server, stack)
        if (level == null) {
            Constants.LOG.warn("Panel wanted the Age of a book in {}'s hand and it would not open", player.name.string)
            return
        }
        val dimension = level.dimension()

        val around = arrivalIn(level)
        val centre = ChunkPos(SectionPos.blockToSectionCoord(around.x), SectionPos.blockToSectionCoord(around.z))
        watching[player.uuid] = View(dimension, centre)
        hold(level, centre)

        // **One line per panel opened, at info.** A book opened once is not noise, and the alternative was
        // what happened the first two times this ran: a black panel, thirty seconds of waiting, and nothing
        // in the log to say whether anything had been asked for at all.
        Constants.LOG.info(
            "Panel opened onto {} for {}, streaming {} chunks around {}",
            dimension.identifier(), player.name.string, PanelProtocol.RING_CHUNKS, around,
        )
        Services.NETWORK.sendToPlayer(
            player,
            PanelLevelPayload(
                dimension = dimension,
                dimensionType = dimensionTypeIdOf(level),
                around = around,
                biomeZoomSeed = BiomeManager.obfuscateSeed(level.seed),
                seaLevel = level.seaLevel,
                chunksComing = PanelProtocol.RING_CHUNKS,
            ),
        )
        sendRing(player, level, centre)
    }

    /** Releases whatever [player] was looking at, if anything. Safe to call when there is nothing. */
    fun close(server: MinecraftServer, player: ServerPlayer) {
        val view = watching.remove(player.uuid) ?: return
        val level = server.getLevel(view.dimension) ?: return
        release(level, view.centre)
    }

    /** Called when a player leaves, since a client that crashed with a book open never says so. */
    fun forget(server: MinecraftServer, player: ServerPlayer) {
        close(server, player)
        lastOpened.remove(player.uuid)
    }

    /**
     * The point a visitor would arrive at, which is what the orbit is centred on.
     *
     * **`Ages.arrivalIn`, and deliberately the same call the link itself makes** — a panel that framed a
     * different place from the one it puts you would be worse than no panel. It is the *arrival* rather
     * than a good view, so an Age that lands you underwater or buried shows water or rock
     * (`link-panel-research.md`). That is honest; the panel says what is there.
     */
    private fun arrivalIn(level: ServerLevel): BlockPos = Ages.arrivalIn(level)

    private fun dimensionTypeIdOf(level: ServerLevel) =
        level.registryAccess()
            .lookupOrThrow(net.minecraft.core.registries.Registries.DIMENSION_TYPE)
            .getKey(level.dimensionType())
            ?: net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD.identifier()

    private fun hold(level: ServerLevel, centre: ChunkPos) {
        level.chunkSource.addTicketWithRadius(PANEL_TICKET, centre, PanelProtocol.RING_RADIUS_CHUNKS + 1)
    }

    private fun release(level: ServerLevel, centre: ChunkPos) {
        level.chunkSource.removeTicketWithRadius(PANEL_TICKET, centre, PanelProtocol.RING_RADIUS_CHUNKS + 1)
    }

    /**
     * Sends every chunk of the ring — the order, and the reason for it, are [PanelProtocol.ringAround]'s.
     *
     * **Synchronous, and that is a known cost rather than an oversight.** `getChunk` blocks until a chunk
     * is generated, so opening a panel onto an Age nobody has visited generates the ring on the server
     * thread while everything else waits. It is bounded — one ring, and the same terrain a visitor would
     * have made by walking there — and it is rate-limited above so it cannot be provoked in a loop. The
     * fix, when it is wanted, is `getChunkFuture` and sending each chunk as it completes; that turns one
     * stall into a stream and wants a tick hook to drain, which is more machinery than a first version of
     * a luxury feature has earned.
     */
    private fun sendRing(player: ServerPlayer, level: ServerLevel, centre: ChunkPos) {
        for (position in PanelProtocol.ringAround(centre)) {
            val chunk = level.getChunk(position.x, position.z)
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
    }
}

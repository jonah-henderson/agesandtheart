package co.voik.agesandtheart.book.panel

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.generation.Ages
import co.voik.agesandtheart.book.BookAge
import co.voik.agesandtheart.book.BookBeingRead
import co.voik.agesandtheart.book.BookEntity
import co.voik.agesandtheart.book.LecternBooks
import co.voik.agesandtheart.book.Linking
import co.voik.agesandtheart.content.AgeComponents
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.desk.CrystalViewerMenu
import co.voik.agesandtheart.desk.PreviewedAges
import co.voik.agesandtheart.desk.ViewerFinding
import co.voik.agesandtheart.location
import co.voik.agesandtheart.platform.Services
import net.minecraft.util.Util
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.TicketType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.BiomeManager
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.status.ChunkStatus
import java.util.UUID

/**
 * Who is looking at what, and the ring of chunks that costs.
 *
 * One panel per player, held only while a book is open (design §7.8.1) — in their hand, or lying open on a
 * lectern or the ground where they are standing (§7.8.2). Everything it loads it releases: opening a second
 * panel closes the first, closing the book closes it, walking away from one lying open closes it, and leaving
 * the server closes it.
 */
object PanelViews {

    /** Registered at init by each loader — see [AgeContent.PANEL_TICKET] for why it cannot live here. */
    private val PANEL_TICKET: TicketType get() = AgeContent.PANEL_TICKET

    /** One player's open panel: what it is looking at, and everything owed to it. */
    private class Watch(
        val dimension: ResourceKey<Level>,
        val centre: ChunkPos,
        /** The book lying open, on a lectern or fallen, or null for one a screen holds — which closes it. */
        val lyingOpen: LyingOpen?,
    )

    /** A book lying open, and the world it lies in. */
    private class LyingOpen(val book: BookBeingRead, val world: ResourceKey<Level>)

    /** What a panel looks at: the world a book leads to, and the point in it a visitor would arrive at. */
    private class Destination(val level: ServerLevel, val around: BlockPos)

    private val watching = mutableMapOf<UUID, Watch>()

    /**
     * How often each player may open a panel. It outlives the view it opened: closing the book removes the
     * watch, and a limit a close resets is not a limit.
     */
    private val pacing = PanelPacing(TICKS_BETWEEN_PANELS)

    /** And when each last asked again, for the same reason and by the same guard. */
    private val chasedOnTick = mutableMapOf<UUID, Long>()

    private const val TICKS_BETWEEN_PANELS = 10L

    /** Re-requests are cheaper than an open but still ask the chunk source for work. */
    private const val TICKS_BETWEEN_CHASES = 20L

    /**
     * Opens a panel onto [book] — in [player]'s hand, or lying open where they are standing — streaming the ring
     * around where it would put them.
     */
    fun open(server: MinecraftServer, player: ServerPlayer, book: BookBeingRead) {
        if (!pacing.admit(player.uuid, book, server.overworld().gameTime)) return
        openAdmitted(server, player, book)
    }

    /** The open itself, once [pacing] has let it through — at once, or from [tick] when a held one comes due. */
    private fun openAdmitted(server: MinecraftServer, player: ServerPlayer, book: BookBeingRead) {
        closeWatchOf(server, player.uuid)

        val destination = destinationFor(server, player, book) ?: return

        val level = destination.level
        val around = destination.around
        val centre = PanelRing.centreOf(around)
        val lyingOpen = LyingOpen(book, player.level().dimension()).takeIf { isLyingOpen(book) }
        watching[player.uuid] = Watch(level.dimension(), centre, lyingOpen)
        hold(level, centre)

        val instability = instabilityOf(level)
        Constants.LOG.info(
            "Panel opened onto {} for {} at instability {}, streaming {} chunks around {}",
            level.dimension().identifier(), player.name.string, instability, PanelRing.COUNT, around,
        )
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
                instability = instability,
            ),
        )
        // **Asked from anywhere but the server thread, and that is the whole of why.**
        // `ServerChunkCache.getChunkFuture` only returns a future when it is called from another thread;
        // on the server thread it runs `managedBlock` and does not come back until the chunk is generated.
        // So asking for a ring there generated the whole of it in one blocking run — the game frozen for
        // its duration, and the panel shown nothing until the end of it.
        Util.backgroundExecutor().execute {
            PanelRing.around(centre).forEach { send(server, player, level, centre, it) }
        }
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

    /**
     * Releases whatever [player] was looking at, and drops any open still held for them, since they have stopped
     * wanting it. Safe to call when there is nothing.
     */
    fun close(server: MinecraftServer, player: ServerPlayer) {
        pacing.cancel(player.uuid)
        closeWatchOf(server, player.uuid)
    }

    /** Called when a player leaves, since a client that crashed with a book open never says so. */
    fun forget(server: MinecraftServer, player: ServerPlayer) {
        close(server, player)
        pacing.forget(player.uuid)
        chasedOnTick.remove(player.uuid)
    }

    /**
     * Opens whatever [pacing] was holding and now lets through, and lets go of the panel of a book lying open
     * whose viewer has walked away from it, or which has shut or gone.
     *
     * The client lets go first and should always be the one to; the second half is for a client that does
     * not, as [forget] is for one that crashed. A panel in a hand belongs to its screen and is left alone.
     */
    fun tick(server: MinecraftServer) {
        for ((viewer, book) in pacing.due(server.overworld().gameTime)) {
            server.playerList.getPlayer(viewer)?.let { openAdmitted(server, it, book) }
        }
        if (watching.isEmpty()) return
        val leftBehind = watching.filter { (viewer, watch) -> isLeftBehind(server, viewer, watch) }.keys.toList()
        for (viewer in leftBehind) {
            Constants.LOG.info("Panel: an open book's panel was still held by {} after they left it", viewer)
            closeWatchOf(server, viewer)
        }
    }

    private fun isLeftBehind(server: MinecraftServer, viewer: UUID, watch: Watch): Boolean {
        val lyingOpen = watch.lyingOpen ?: return false
        val player = server.playerList.getPlayer(viewer) ?: return true
        val inTheBooksWorld = player.level().dimension() == lyingOpen.world
        return !inTheBooksWorld || bookSeenBy(player, lyingOpen.book) == null
    }

    private fun isLyingOpen(book: BookBeingRead): Boolean = when (book) {
        is BookBeingRead.OnALectern, is BookBeingRead.OnTheGround -> true
        is BookBeingRead.InHand, BookBeingRead.AtACrystalViewer -> false
    }

    /** The book [player] is reading as [book], or null where it is not there for them to read. */
    private fun bookSeenBy(player: ServerPlayer, book: BookBeingRead): ItemStack? = when (book) {
        is BookBeingRead.InHand -> player.getItemInHand(book.hand)
        is BookBeingRead.OnALectern -> LecternBooks.openBookSeenBy(player, book.pos)
        is BookBeingRead.OnTheGround -> BookEntity.openBookSeenBy(player, book.entityId)
        BookBeingRead.AtACrystalViewer -> null
    }

    private fun closeWatchOf(server: MinecraftServer, viewer: UUID) {
        val watch = watching.remove(viewer) ?: return
        val level = server.getLevel(watch.dimension) ?: return
        release(level, watch.centre)
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
     * What [player]'s panel onto [book] looks at, or null — said in the log — where it looks at nothing.
     *
     * A crystal viewer's is the Age its screen was opened onto ([PreviewedAges]), and only while that screen
     * is open: the menu is the proof the writer is standing at one.
     */
    private fun destinationFor(server: MinecraftServer, player: ServerPlayer, book: BookBeingRead): Destination? {
        if (book == BookBeingRead.AtACrystalViewer) {
            // The menu's own finding, so a viewer opened onto an unwritable sentence cannot show the last
            // preview the writer made.
            val viewer = player.containerMenu as? CrystalViewerMenu
            val isPreviewing = viewer?.finding == ViewerFinding.PREVIEWING
            val level = if (isPreviewing) PreviewedAges.showing(player) else null
            if (level == null) Constants.LOG.info("Panel refused: {} asked after a crystal viewer with none open, or nothing laid out", player.name.string)
            return level?.let { Destination(it, Ages.arrivalIn(it)) }
        }
        val stack = bookSeenBy(player, book)
        if (stack == null) {
            Constants.LOG.info("Panel refused: {} asked after {}, with no open book of ours there in reach", player.name.string, book)
            return null
        }
        val destination = destinationOf(server, stack)
        if (destination == null) {
            Constants.LOG.info("Panel refused: {} asked after {}, which leads nowhere that will open", player.name.string, stack.item)
            return null
        }
        // Resolving a descriptive book stamps its Age onto it, and a lectern keeps the stamp.
        if (book is BookBeingRead.OnALectern) player.level().getBlockEntity(book.pos)?.setChanged()
        return destination
    }

    /**
     * Where [stack] would put you, resolved by the same calls linking makes — `BookAge` and the Age's arrival
     * for a descriptive book, the target's own world and spot for a linking one — so a panel cannot frame
     * anywhere other than where the book takes you, including when that is underwater or buried.
     */
    private fun destinationOf(server: MinecraftServer, stack: ItemStack): Destination? = when {
        stack.item === AgeContent.DESCRIPTIVE_BOOK ->
            BookAge.of(server, stack)?.let { Destination(it, Ages.arrivalIn(it)) }
        stack.item === AgeContent.LINKING_BOOK -> {
            val target = stack.get(AgeComponents.LINK_TARGET)
            val level = target?.let { Linking.destinationOf(it, server) }
            if (target == null || level == null) null else Destination(level, BlockPos.containing(target.position))
        }
        else -> null
    }

    private fun dimensionTypeIdOf(level: ServerLevel) =
        level.registryAccess()
            .lookupOrThrow(net.minecraft.core.registries.Registries.DIMENSION_TYPE)
            .getKey(level.dimensionType())
            ?: net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD.identifier()

    /** The Age's index, and nought for a level that is not one of ours — the Spire has no book. */
    private fun instabilityOf(level: ServerLevel): Int = Ages.recipeOf(level)?.instability?.index ?: 0

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
        level.chunkSource.getChunkFuture(position.x, position.z, ChunkStatus.FULL, true)
            .thenAcceptAsync({ result ->
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
                val stillWatchingThisRing = watch != null && watch.centre == centre && watch.dimension == level.dimension()
                if (!stillWatchingThisRing) return@thenAcceptAsync
                Services.NETWORK.sendToPlayer(
                    player,
                    PanelChunkPayload(
                        x = position.x,
                        z = position.z,
                        chunk = ClientboundLevelChunkPacketData(chunk),
                        light = ClientboundLightUpdatePacketData(position, level.lightEngine, null, null),
                    ),
                )
            }, server)
    }
}

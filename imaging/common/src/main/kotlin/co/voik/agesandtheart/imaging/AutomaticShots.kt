package co.voik.agesandtheart.imaging

import co.voik.agesandtheart.platform.Services
import co.voik.agesandtheart.book.panel.LevelPreviews
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Util
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.biome.BiomeManager
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.phys.Vec3
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Pictures a set-down recorder took, waiting for a client to draw them. Nobody is behind a recorder, so the server asks
 * a client to: the recorder's owner where they are online, or whoever has least waiting for them, nearest first. It
 * streams that client the chunks the recorder looks out on ([PhotographRing]), the client draws them in a level of its
 * own from the recorder's lens and sends the picture back ([PhotographPiece]), and it is developed as a handheld
 * photograph is. A client draws one at a time, the rest waiting their turn.
 *
 * The server's own sketch stands in where pictures are not shared, since there is nobody whose upload to trust; where
 * nobody is online; and for a photograph that has not come back within a minute of being asked for.
 */
object AutomaticShots {

    private class Commission(val id: UUID, val owner: UUID?, val pose: Shots.Pose) {
        var askedOnTick = NOT_ASKED
    }

    private class Arrival(val id: UUID, val count: Int) {
        val pieces = arrayOfNulls<ByteArray>(count)
        var received = 0
    }

    /** Each photographer's commissions, the first the one they are drawing. */
    private val waiting = HashMap<UUID, ArrayDeque<Commission>>()
    private val arriving = HashMap<UUID, Arrival>()

    private const val NOT_ASKED = -1

    /** More waiting for one client than this and the newest is sketched: the recorders are firing faster than it draws. */
    private const val MOST_WAITING = 8

    /** A minute at twenty ticks a second, from being asked: the chunks come and are drawn in seconds. */
    private const val TICKS_TO_DEVELOP = 1200

    /** A hundred pieces of 30,000 bytes, three times the largest picture a client should send. */
    private const val MOST_PIECES = 100

    /** Takes the picture [id] from [pose], for [owner]: asked of a client, or sketched where none may draw it. */
    fun commission(level: ServerLevel, id: UUID, owner: UUID?, pose: Shots.Pose) {
        val server = level.server
        val commission = Commission(id, owner, pose)
        val photographer = if (ImagingConfig.sharesPictures.get()) photographerFor(server, commission) else null
        val queue = photographer?.let { waiting.getOrPut(it.uuid) { ArrayDeque() } }
        if (photographer == null || queue == null || queue.size >= MOST_WAITING) {
            sketch(server, commission)
            return
        }
        queue.addLast(commission)
        askIfDue(server, photographer, queue)
    }

    /** The owner where they are online; otherwise whoever has least waiting, in the recorder's level and nearest first. */
    private fun photographerFor(server: MinecraftServer, commission: Commission): ServerPlayer? {
        val players = server.playerList.players
        players.firstOrNull { it.uuid == commission.owner }?.let { return it }
        val lens = Vec3(commission.pose.eyeX, commission.pose.eyeY, commission.pose.eyeZ)
        fun waitingFor(player: ServerPlayer) = waiting[player.uuid]?.size ?: 0
        fun isElsewhere(player: ServerPlayer) = player.level().dimension() != commission.pose.dimension
        return players.minWithOrNull(
            compareBy<ServerPlayer>(::waitingFor).thenBy(::isElsewhere).thenBy { it.distanceToSqr(lens) },
        )
    }

    fun tick(server: MinecraftServer) {
        for ((photographer, queue) in waiting) {
            val player = server.playerList.getPlayer(photographer)
            if (player == null) {
                queue.forEach { sketch(server, it) }
                queue.clear()
                arriving.remove(photographer)
                continue
            }
            val first = queue.firstOrNull() ?: continue
            val hasTakenTooLong = first.askedOnTick != NOT_ASKED && server.tickCount - first.askedOnTick > TICKS_TO_DEVELOP
            if (hasTakenTooLong) {
                Imaging.LOG.info("A recorder's photograph asked of {} did not come back, so it is sketched", player.name.string)
                sketch(server, queue.removeFirst())
                arriving.remove(photographer)
            }
            askIfDue(server, player, queue)
        }
        waiting.values.removeIf { it.isEmpty() }
    }

    fun pieceArrived(player: ServerPlayer, piece: PhotographPiece) {
        val isAPlausiblePiece = piece.count in 1..MOST_PIECES && piece.index in 0..<piece.count
        val queue = waiting[player.uuid]
        val drawing = queue?.firstOrNull()
        val isForWhatTheyAreDrawing = drawing != null && drawing.id == piece.id && drawing.askedOnTick != NOT_ASKED
        if (!isAPlausiblePiece || queue == null || !isForWhatTheyAreDrawing) return
        val current = arriving[player.uuid]
        val arrival = if (current == null || current.id != piece.id || current.count != piece.count) {
            Arrival(piece.id, piece.count).also { arriving[player.uuid] = it }
        } else {
            current
        }
        if (arrival.pieces[piece.index] == null) arrival.received++
        arrival.pieces[piece.index] = piece.bytes
        if (arrival.received < arrival.count) return
        arriving.remove(player.uuid)
        val commission = queue.removeFirst()
        val upload = ByteArrayOutputStream().apply { arrival.pieces.forEach { write(it ?: return) } }.toByteArray()
        val server = player.level().server
        val about = aboutOf(commission, drawnBy = player.uuid)
        Util.backgroundExecutor().execute { PictureStore.develop(server, commission.id, upload, about) }
        Sketches.draw(server, commission.id, about)
        askIfDue(server, player, queue)
    }

    private fun aboutOf(commission: Commission, drawnBy: UUID?) = PictureStore.About(
        photographer = commission.owner ?: drawnBy ?: NOBODY,
        pose = commission.pose,
        fieldOfView = Sketches.ORDINARY_FIELD_OF_VIEW,
        takenAt = System.currentTimeMillis(),
    )

    private fun sketch(server: MinecraftServer, commission: Commission) = Sketches.draw(server, commission.id, aboutOf(commission, drawnBy = null))

    /** Asks [player] for the first of [queue] where they have not been asked for it yet. */
    private fun askIfDue(server: MinecraftServer, player: ServerPlayer, queue: ArrayDeque<Commission>) {
        val first = queue.firstOrNull() ?: return
        if (first.askedOnTick != NOT_ASKED) return
        val level = server.getLevel(first.pose.dimension)
        if (level == null) {
            sketch(server, queue.removeFirst())
            askIfDue(server, player, queue)
            return
        }
        first.askedOnTick = server.tickCount
        val eye = Vec3(first.pose.eyeX, first.pose.eyeY, first.pose.eyeZ)
        val chunks = PhotographRing.around(eye, first.pose.yaw)
        Services.NETWORK.sendToPlayer(player, wantedOf(level, first, chunks.size))
        LevelPreviews.tellTheClock(player, level)
        // Asked from off the server thread, where `getChunkFuture` returns a future rather than generating the chunk
        // there and then, as a linking panel's ring is.
        Util.backgroundExecutor().execute { chunks.forEach { send(server, player, level, first, it) } }
    }

    private fun wantedOf(level: ServerLevel, commission: Commission, chunksComing: Int): PhotographWanted {
        val pose = commission.pose
        return PhotographWanted(
            id = commission.id,
            dimension = level.dimension(),
            dimensionType = LevelPreviews.dimensionTypeOf(level),
            eyeX = pose.eyeX,
            eyeY = pose.eyeY,
            eyeZ = pose.eyeZ,
            yaw = pose.yaw,
            pitch = pose.pitch,
            biomeZoomSeed = BiomeManager.obfuscateSeed(level.seed),
            seaLevel = level.seaLevel,
            gameTime = level.gameTime,
            rain = level.getRainLevel(WHOLE_TICK),
            thunder = level.getThunderLevel(WHOLE_TICK),
            chunksComing = chunksComing,
            isFlat = level.isFlat,
        )
    }

    /** One chunk, sent once it is ready, and only while [player] is still drawing [commission]. */
    private fun send(server: MinecraftServer, player: ServerPlayer, level: ServerLevel, commission: Commission, position: ChunkPos) {
        level.chunkSource.getChunkFuture(position.x, position.z, ChunkStatus.FULL, true)
            .thenAcceptAsync({ result ->
                val chunk = result.orElse(null) as? LevelChunk ?: return@thenAcceptAsync
                val isStillDrawingIt = waiting[player.uuid]?.firstOrNull() === commission
                if (!isStillDrawingIt || player.hasDisconnected()) return@thenAcceptAsync
                val payload = PhotographChunk(
                    id = commission.id,
                    x = position.x,
                    z = position.z,
                    chunk = ClientboundLevelChunkPacketData(chunk),
                    light = ClientboundLightUpdatePacketData(position, level.lightEngine, null, null),
                )
                Services.NETWORK.sendToPlayer(player, payload)
            }, server)
    }

    private val NOBODY = UUID(0L, 0L)

    /** A level's weather read whole, with no tick between two of its steps. */
    private const val WHOLE_TICK = 1.0f
}

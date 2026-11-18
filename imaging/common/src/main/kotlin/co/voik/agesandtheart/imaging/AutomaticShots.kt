package co.voik.agesandtheart.imaging

import co.voik.agesandtheart.platform.Services
import co.voik.agesandtheart.book.panel.LevelPreviews
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket
import net.minecraft.server.level.ServerEntity
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.UpdateInterval
import net.minecraft.world.phys.AABB
import java.util.function.Predicate
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
 * streams that client the chunks the recorder looks out on, as far as that client's render distance ([PhotographRing]), the client draws them in a level of its
 * own from the recorder's lens and sends the picture back ([PhotographPiece]), and it is developed as a handheld
 * photograph is. A client draws one at a time, the rest waiting their turn.
 *
 * The server's own sketch stands in where pictures are not shared, since there is nobody whose upload to trust; where
 * nobody is online; and for a photograph that has not come back within two minutes of being asked for.
 */
object AutomaticShots {

    private class Commission(val id: UUID, val owner: UUID?, val pose: Shots.Pose, val fieldOfView: Float) {
        /** How far it sees, which is how far its photographer sees: set as it is given to one. */
        var shownRadius = PhotographRing.LEAST_SHOWN_RADIUS_CHUNKS

        var askedOnTick = NOT_ASKED

        /** What stood before the lens as the shutter went, taken then and sent after the chunks. */
        var entities: PhotographEntities? = null
        var entitiesSent = false

        /** Chunks asked of the chunk source and not yet answered, and those ready to send, a few each tick. */
        var chunksAwaited = 0
        val outbox = ArrayDeque<LevelChunk>()
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

    /**
     * Two minutes at twenty ticks a second, from being asked: the chunks come and are drawn in seconds, but a far render
     * distance is hundreds of chunks, some perhaps still to be generated.
     */
    private const val TICKS_TO_DEVELOP = 2400

    /** A hundred pieces of 30,000 bytes, three times the largest picture a client should send. */
    private const val MOST_PIECES = 100

    /**
     * Takes the picture [id] from [pose] at [fieldOfView], for [owner]: asked of a client, or sketched where none may
     * draw it.
     */
    fun commission(level: ServerLevel, id: UUID, owner: UUID?, pose: Shots.Pose, fieldOfView: Float) {
        val server = level.server
        val commission = Commission(id, owner, pose, fieldOfView)
        val photographer = if (ImagingConfig.sharesPictures.get()) photographerFor(server, commission) else null
        val queue = photographer?.let { waiting.getOrPut(it.uuid) { ArrayDeque() } }
        if (photographer == null || queue == null || queue.size >= MOST_WAITING) {
            sketch(server, commission)
            return
        }
        commission.shownRadius = howFarSees(photographer)
        commission.entities = snapshotOf(level, commission, photographer)
        queue.addLast(commission)
        askIfDue(server, photographer, queue)
    }

    /**
     * The entities ahead of the lens, nearest first, as the packets that bring each into [photographer]'s view, built by
     * the same `ServerEntity` vanilla tracks one with: what it is and where, its data and its equipment.
     */
    private fun snapshotOf(level: ServerLevel, commission: Commission, photographer: ServerPlayer): PhotographEntities {
        val pose = commission.pose
        val lens = Vec3(pose.eyeX, pose.eyeY, pose.eyeZ)
        val reach = commission.shownRadius * BLOCKS_A_CHUNK.toDouble()
        val forward = Vec3.directionFromRotation(0.0f, pose.yaw)
        fun isNotBehind(entity: Entity) = entity.position().subtract(lens).dot(forward) >= -BEHIND_THE_LENS
        val inView = level.getEntities(null as Entity?, AABB.ofSize(lens, reach * 2, reach * 2, reach * 2)) { !it.isRemoved }
            .filter(::isNotBehind)
            .sortedBy { it.distanceToSqr(lens) }
            .take(MOST_ENTITIES)
        val packets = mutableListOf<Packet<*>>()
        for (entity in inView) {
            // A lambda rather than `packets::add`: NeoForge widens the consumer's type, and a lambda fits either.
            ServerEntity(level, entity, UpdateInterval.NEVER, false, NOBODY_TRACKING).sendPairingData(photographer) { packets.add(it) }
        }
        return PhotographEntities(
            id = commission.id,
            arrivals = packets.filterIsInstance<ClientboundAddEntityPacket>(),
            data = packets.filterIsInstance<ClientboundSetEntityDataPacket>(),
            equipment = packets.filterIsInstance<ClientboundSetEquipmentPacket>(),
        )
    }

    /** A tracker that tells nobody anything: a snapshot is sent by hand, once. */
    private val NOBODY_TRACKING = object : ServerEntity.Synchronizer {
        override fun sendToTrackingPlayers(packet: Packet<in ClientGamePacketListener>) = Unit
        override fun sendToTrackingPlayersAndSelf(packet: Packet<in ClientGamePacketListener>) = Unit
        override fun sendToTrackingPlayersFiltered(packet: Packet<in ClientGamePacketListener>, predicate: Predicate<ServerPlayer>) = Unit
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
            queue.firstOrNull()?.takeIf { it.askedOnTick != NOT_ASKED }?.let { sendSome(player, it) }
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

    /**
     * How far [photographer] sees: the render distance their game asked for, as far as the server lets anyone see, so a
     * photograph ends where their view through the recorder did.
     */
    private fun howFarSees(photographer: ServerPlayer): Int {
        val serverLimit = photographer.level().server.playerList.viewDistance
        return minOf(photographer.requestedViewDistance(), serverLimit).coerceAtLeast(PhotographRing.LEAST_SHOWN_RADIUS_CHUNKS)
    }

    private fun aboutOf(commission: Commission, drawnBy: UUID?) = PictureStore.About(
        photographer = commission.owner ?: drawnBy ?: NOBODY,
        pose = commission.pose,
        fieldOfView = commission.fieldOfView,
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
        val chunks = PhotographRing.around(eye, first.pose.yaw, first.shownRadius)
        Services.NETWORK.sendToPlayer(player, wantedOf(level, first, chunks.size))
        LevelPreviews.tellTheClock(player, level)
        // Asked from off the server thread, where `getChunkFuture` returns a future rather than generating the chunk
        // there and then, as a linking panel's ring is; each is answered on the server thread, and sent from the tick.
        first.chunksAwaited = chunks.size
        Util.backgroundExecutor().execute { chunks.forEach { ask(server, level, first, it) } }
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
            shownRadiusChunks = commission.shownRadius,
            isFlat = level.isFlat,
            fieldOfView = commission.fieldOfView,
        )
    }

    /**
     * One chunk asked for, and put in [commission]'s outbox once it is ready. A future rather than `getChunk`, which
     * would stop the server until the chunk was loaded or generated.
     */
    private fun ask(server: MinecraftServer, level: ServerLevel, commission: Commission, position: ChunkPos) {
        level.chunkSource.getChunkFuture(position.x, position.z, ChunkStatus.FULL, true)
            .whenCompleteAsync({ result, _ ->
                commission.chunksAwaited--
                (result?.orElse(null) as? LevelChunk)?.let(commission.outbox::addLast)
            }, server)
    }

    /**
     * A few of [commission]'s ready chunks sent to [player] each tick rather than all as they come: each is written out on
     * the server thread, and a photograph is hundreds of them, which all at once stalls the server. Its entities go
     * after the last, so each has ground to stand in.
     */
    private fun sendSome(player: ServerPlayer, commission: Commission) {
        val level = player.level().server.getLevel(commission.pose.dimension) ?: return
        repeat(CHUNKS_A_TICK) {
            val chunk = commission.outbox.removeFirstOrNull() ?: return@repeat
            val payload = PhotographChunk(
                id = commission.id,
                x = chunk.pos.x,
                z = chunk.pos.z,
                chunk = ClientboundLevelChunkPacketData(chunk),
                light = ClientboundLightUpdatePacketData(chunk.pos, level.lightEngine, null, null),
            )
            Services.NETWORK.sendToPlayer(player, payload)
        }
        val isEverythingSent = commission.chunksAwaited == 0 && commission.outbox.isEmpty()
        if (!isEverythingSent || commission.entitiesSent) return
        commission.entities?.let { Services.NETWORK.sendToPlayer(player, it) }
        commission.entitiesSent = true
    }

    private val NOBODY = UUID(0L, 0L)

    /** How many of a photograph's chunks go to its photographer each tick: a few hundred in a second or so. */
    private const val CHUNKS_A_TICK = 16

    /** As many entities as a photograph shows, the nearest; a crowd past this is a farm, and its far end is fog. */
    private const val MOST_ENTITIES = 256
    private const val BLOCKS_A_CHUNK = 16

    /** How far behind the lens an entity may stand and still be in the snapshot, for one leaning into the frame. */
    private const val BEHIND_THE_LENS = 2.0

    /** A level's weather read whole, with no tick between two of its steps. */
    private const val WHOLE_TICK = 1.0f
}

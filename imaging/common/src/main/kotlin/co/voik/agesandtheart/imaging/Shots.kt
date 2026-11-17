package co.voik.agesandtheart.imaging

import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Util
import net.minecraft.world.level.Level
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Pictures taken and waiting for the photographer's client to send them up.
 *
 * The server knows a picture was taken when the recorder is used; the client knows what it looked like a frame later.
 * Each player's shots wait in the order they were taken, and each upload that arrives whole is the oldest one's. An
 * upload with no shot waiting for it is dropped, so nothing reaches the store that a plate was not spent on.
 */
object Shots {

    /** Where and which way a picture was taken from, for the server's own drawing of it where pictures are not shared. */
    data class Pose(val dimension: ResourceKey<Level>, val eyeX: Double, val eyeY: Double, val eyeZ: Double, val yaw: Float, val pitch: Float)

    private class Shot(val id: UUID, val pose: Pose, val takenOnTick: Int)

    private class Arrival(val count: Int, val fieldOfView: Float) {
        val pieces = arrayOfNulls<ByteArray>(count)
        var received = 0
    }

    private val waiting = HashMap<UUID, ArrayDeque<Shot>>()
    private val arriving = HashMap<UUID, Arrival>()

    /** More than this many shots waiting means an upload went astray; the oldest is let go. */
    private const val MOST_WAITING = 4

    /** A minute at twenty ticks a second: an upload takes a moment, and one that has not come in a minute is not coming. */
    private const val TICKS_TO_ARRIVE = 1200

    /** A hundred pieces of 30,000 bytes: three times the largest picture a client should send. */
    private const val MOST_PIECES = 100

    fun taken(player: ServerPlayer, id: UUID) {
        val server = player.level().server
        val pose = Pose(player.level().dimension(), player.x, player.eyeY, player.z, player.yRot, player.xRot)
        val shots = waiting.getOrPut(player.uuid) { ArrayDeque() }
        shots.removeAll { server.tickCount - it.takenOnTick > TICKS_TO_ARRIVE }
        while (shots.size >= MOST_WAITING) shots.removeFirst()
        shots.addLast(Shot(id, pose, server.tickCount))
    }

    fun pieceArrived(player: ServerPlayer, piece: PicturePiece) {
        val isAPlausiblePiece = piece.count in 1..MOST_PIECES && piece.index in 0..<piece.count
        if (!isAPlausiblePiece || waiting[player.uuid].isNullOrEmpty()) return
        val current = arriving[player.uuid]
        val arrival = if (current == null || current.count != piece.count || piece.index == 0) {
            Arrival(piece.count, piece.fieldOfView).also { arriving[player.uuid] = it }
        } else {
            current
        }
        if (arrival.pieces[piece.index] == null) arrival.received++
        arrival.pieces[piece.index] = piece.bytes
        if (arrival.received < arrival.count) return
        arriving.remove(player.uuid)
        val shot = waiting[player.uuid]?.removeFirstOrNull() ?: return
        val upload = ByteArrayOutputStream().apply { arrival.pieces.forEach { write(it ?: return) } }.toByteArray()
        val server = player.level().server
        val about = PictureStore.About(
            photographer = player.uuid,
            pose = shot.pose,
            fieldOfView = arrival.fieldOfView,
            takenAt = System.currentTimeMillis(),
        )
        Util.backgroundExecutor().execute { PictureStore.develop(server, shot.id, upload, about) }
    }
}

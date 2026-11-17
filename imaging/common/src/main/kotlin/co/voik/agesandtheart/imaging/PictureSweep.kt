package co.voik.agesandtheart.imaging

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Util
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps a save's pictures within the space [ImagingConfig] gives them: every ten minutes, and as the server starts, if
 * they take more, the pictures seen least lately give up their full and middle sizes and keep their thumbnail and
 * sketch until they fit. Nothing is deleted outright, since a recorded image can sit in an unloaded chest for as long as
 * it likes, and nothing seen in the last day is touched.
 *
 * And the sightings that reads: what each player carries is looked over at the same interval, as hung pictures and
 * carousels report themselves as they load and a client's fetch reports itself.
 */
object PictureSweep {

    private const val TICKS_BETWEEN_SWEEPS = 12_000
    private const val SPARED_FOR_MILLIS = 24L * 60L * 60L * 1000L
    private const val BYTES_A_MEGABYTE = 1024L * 1024L

    private val isSweeping = AtomicBoolean(false)

    fun tick(server: MinecraftServer) {
        if (server.tickCount % TICKS_BETWEEN_SWEEPS != 1) return
        server.playerList.players.forEach { lookOver(server, it) }
        val megabytes = ImagingConfig.picturesMayTakeMegabytes.get()
        if (megabytes <= 0 || !isSweeping.compareAndSet(false, true)) return
        val seen = PicturesSeen.of(server).snapshot()
        val sparedSince = System.currentTimeMillis() - SPARED_FOR_MILLIS
        Util.ioPool().execute {
            try {
                PictureStore.shrinkToFit(server, seen, megabytes * BYTES_A_MEGABYTE, sparedSince)
            } finally {
                isSweeping.set(false)
            }
        }
    }

    private fun lookOver(server: MinecraftServer, player: ServerPlayer) {
        val seen = PicturesSeen.of(server)
        val inventory = player.inventory
        for (slot in 0..<inventory.containerSize) inventory.getItem(slot).get(ImagingComponents.PICTURE)?.let { seen.saw(it.id) }
    }
}

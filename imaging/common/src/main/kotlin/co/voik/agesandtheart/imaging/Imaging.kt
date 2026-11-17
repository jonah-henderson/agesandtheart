package co.voik.agesandtheart.imaging

import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object Imaging {
    const val MOD_ID = "agesandtheart_imaging"
    val LOG: Logger = LoggerFactory.getLogger("AgesAndTheArtImaging")

    fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(MOD_ID, path)

    /** Everything the companion does each server tick, which each loader calls from its own tick event. */
    fun serverTick(server: MinecraftServer) {
        Viewfinder.serverTick(server)
        Shots.tick(server)
        Sketches.tick(server)
        PictureSweep.tick(server)
    }
}

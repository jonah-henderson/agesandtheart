package co.voik.agesandtheart.desk

import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer

/**
 * The writer's desk in an instrument's own room, and what somebody has laid on it.
 *
 * **Every instrument that reads a sentence needs this and none of them owns it.** The seismograph asks what
 * the ground under a book would be like and the geologist's tools ask what is in it; both are reading the
 * same pages off the same desk, found the same way, and a second copy of the scan would be a second answer
 * to "which desk" for anyone standing between two of them.
 *
 * **Where the desk was found is remembered, so the room is only searched while there is none.** A desk
 * broken while a screen is open is caught by the block entity going missing, which puts this back to
 * nothing and starts the search again — so the one expensive case is the one whose answer is "there is no
 * desk here" anyway.
 */
class NearbyDesk(private val pos: BlockPos) {

    private var deskAt: BlockPos? = null

    /**
     * This writer's pages on the desk in the room, or null where the room holds no desk.
     *
     * An empty list and a null are different answers and both matter: a desk with a bare surface is an
     * instrument with nothing to read, where no desk at all is an instrument that should fall back to the
     * world it is standing in.
     */
    fun laidOutBy(writer: ServerPlayer): List<Identifier>? {
        val level = writer.level()
        val known = deskAt?.let { level.getBlockEntity(it) as? WritersDeskBlockEntity }
        if (known != null) return known.compositionFor(writer.uuid)
        deskAt = null
        val workshop = WritersDesk.load(level.server.resourceManager, mutableListOf())
        val reach = workshop.radius
        val cursor = BlockPos.MutableBlockPos()
        for (x in -reach..reach) for (y in -reach..reach) for (z in -reach..reach) {
            cursor.setWithOffset(pos, x, y, z)
            val desk = level.getBlockEntity(cursor) as? WritersDeskBlockEntity ?: continue
            deskAt = cursor.immutable()
            return desk.compositionFor(writer.uuid)
        }
        return null
    }
}

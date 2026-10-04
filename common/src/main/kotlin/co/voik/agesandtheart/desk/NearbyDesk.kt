package co.voik.agesandtheart.desk

import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
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
     * The words this writer's template at the desk in the room names — the learned ones, which are the
     * ones a book could be bound from — or null where the room holds no desk.
     *
     * An empty list and a null are different answers and both matter: a desk with a bare surface is an
     * instrument with nothing to read, where no desk at all is an instrument that should fall back to the
     * world it is standing in.
     */
    fun laidOutBy(writer: ServerPlayer): List<Identifier>? = readFor(writer)?.let(TemplateReading::learnedWords)

    /**
     * This writer's template at the desk in the room, read word by word — unlearned and unknown runs
     * included, which is what the crystal viewer needs to refuse a sentence the desk would refuse to bind.
     * Null where the room holds no desk.
     */
    fun readFor(writer: ServerPlayer): List<ReadWord>? =
        deskIn(writer.level())?.let { DeskTemplates.read(writer, it.templateFor(writer.uuid)) }

    /** The writer's desk in the room, or null where there is none. */
    fun deskIn(level: ServerLevel): WritersDeskBlockEntity? {
        val known = deskAt?.let { level.getBlockEntity(it) as? WritersDeskBlockEntity }
        if (known != null) return known
        deskAt = null
        val reach = WritersDesk.of(level.server).radius
        val cursor = BlockPos.MutableBlockPos()
        for (x in -reach..reach) for (y in -reach..reach) for (z in -reach..reach) {
            cursor.setWithOffset(pos, x, y, z)
            val desk = level.getBlockEntity(cursor) as? WritersDeskBlockEntity ?: continue
            deskAt = cursor.immutable()
            return desk
        }
        return null
    }
}

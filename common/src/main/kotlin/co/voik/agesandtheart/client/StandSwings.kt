package co.voik.agesandtheart.client

import net.minecraft.world.level.block.entity.BlockEntity
import java.util.WeakHashMap

/**
 * How far each book on a stand of ours has swung open, kept on the client per stand and place for as long as it holds
 * the stand, as [LecternBookRenderer] keeps its lecterns' books. A stand says only whether a book is open; the swing
 * between is the client's. A book first seen is already where its stand says.
 */
object StandSwings {

    private class Swing(var progress: Float, var seenAt: Double)

    private val swings = WeakHashMap<BlockEntity, HashMap<Int, Swing>>()

    /** How open the book at [place] on [stand] is drawn, eased at both ends: shut (0) to open (1). */
    fun opennessOf(stand: BlockEntity, place: Int, isOpen: Boolean, partialTicks: Float): Float {
        val now = (stand.level?.gameTime ?: 0L) + partialTicks.toDouble()
        val swing = swings.getOrPut(stand) { HashMap() }.getOrPut(place) { Swing(if (isOpen) 1.0f else 0.0f, now) }
        val step = ((now - swing.seenAt) / LecternBookRenderer.SWING_TICKS).toFloat().coerceAtLeast(0.0f)
        swing.progress = (if (isOpen) swing.progress + step else swing.progress - step).coerceIn(0.0f, 1.0f)
        swing.seenAt = now
        return swing.progress * swing.progress * (3 - 2 * swing.progress)
    }
}

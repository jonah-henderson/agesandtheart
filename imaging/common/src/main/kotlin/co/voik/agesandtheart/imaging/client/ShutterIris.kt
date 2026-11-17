package co.voik.agesandtheart.imaging.client

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.util.Mth
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The shutter seen from behind the lens: a six-bladed iris closing over the view and opening again, turning a little as
 * its blades do. Drawn over the HUD's place once the picture's frame is already taken, so it is never in the picture.
 * The opening is a hexagon, filled around a row at a time.
 */
object ShutterIris {

    private const val CLOSING_MILLIS = 110L
    private const val SHUT_MILLIS = 40L
    private const val OPENING_MILLIS = 130L
    private const val BLADES = 6
    private const val TURN_WHILE_CLOSING = 0.5f
    private const val BLACK = 0xFF000000.toInt()

    private var startedAt = -1L

    fun start() {
        startedAt = System.currentTimeMillis()
    }

    @JvmStatic
    fun isPlaying(): Boolean = startedAt >= 0 && System.currentTimeMillis() - startedAt < CLOSING_MILLIS + SHUT_MILLIS + OPENING_MILLIS

    @JvmStatic
    fun draw(graphics: GuiGraphicsExtractor) {
        if (!isPlaying()) return
        val width = graphics.guiWidth()
        val height = graphics.guiHeight()
        val open = openness(System.currentTimeMillis() - startedAt)
        // Wide enough open to clear the corners, and nothing at all once shut.
        val radius = open * Mth.sqrt((width * width + height * height).toFloat()) / 2.0f
        val turn = (1.0f - open) * TURN_WHILE_CLOSING
        val centreX = width / 2.0f
        val centreY = height / 2.0f
        for (row in 0..<height) {
            val span = hexagonSpan(row + 0.5f - centreY, radius, turn)
            if (span == null) {
                graphics.fill(0, row, width, row + 1, BLACK)
            } else {
                graphics.fill(0, row, Mth.floor(centreX + span.first), row + 1, BLACK)
                graphics.fill(Mth.ceil(centreX + span.second), row, width, row + 1, BLACK)
            }
        }
    }

    /** How open the iris is, one to nought while it closes, and back. */
    private fun openness(elapsed: Long): Float = when {
        elapsed < CLOSING_MILLIS -> 1.0f - eased(elapsed.toFloat() / CLOSING_MILLIS)
        elapsed < CLOSING_MILLIS + SHUT_MILLIS -> 0.0f
        else -> eased((elapsed - CLOSING_MILLIS - SHUT_MILLIS).toFloat() / OPENING_MILLIS)
    }

    private fun eased(share: Float): Float = share * share * (3.0f - 2.0f * share)

    /** Where a row [y] from the middle crosses the hexagon of corner [radius] turned by [turn], or null where it misses. */
    private fun hexagonSpan(y: Float, radius: Float, turn: Float): Pair<Float, Float>? {
        if (radius <= 0.0f) return null
        var left = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        for (side in 0..<BLADES) {
            val from = turn + side * Mth.TWO_PI / BLADES
            val to = turn + (side + 1) * Mth.TWO_PI / BLADES
            val ax = radius * cos(from)
            val ay = radius * sin(from)
            val bx = radius * cos(to)
            val by = radius * sin(to)
            val crosses = (ay <= y && by >= y) || (by <= y && ay >= y)
            if (!crosses || ay == by) continue
            val x = ax + (y - ay) / (by - ay) * (bx - ax)
            left = min(left, x)
            right = max(right, x)
        }
        return if (left <= right) left to right else null
    }
}

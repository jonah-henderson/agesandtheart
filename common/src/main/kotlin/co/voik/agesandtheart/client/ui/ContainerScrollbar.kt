package co.voik.agesandtheart.client.ui

import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.roundToInt

/**
 * A container's scrollbar after the stonecutter's, narrowed for our panels: a recessed track and a raised knob of
 * fixed height, **placed from where the pointer is** rather than moved by how far it went. Vanilla's list bar moves
 * by a scaled delta floored at one, so it runs ahead of the pointer whenever a list overflows by less than the bar's
 * own travel, which is most lists in a container. Pressing the track brings the knob's middle to the pointer, as the
 * stonecutter's does.
 *
 * Positions are a fraction of the way down, so the list or text area owning it keeps its own scroll amount.
 */
class ContainerScrollbar {

    /** How far down the knob the pointer took hold of it, while it is held. */
    private var heldAt: Double? = null

    val isHeld: Boolean get() = heldAt != null

    fun draw(graphics: GuiGraphicsExtractor, track: Rect, fraction: Double) {
        drawRecess(graphics, track)
        drawKnob(graphics, knobOn(track, fraction))
    }

    /** A press at [mouseY] on [track], with the knob at [fraction]: takes hold of it, and answers where it now is. */
    fun press(track: Rect, fraction: Double, mouseY: Double): Double {
        val knob = knobOn(track, fraction)
        val onTheKnob = mouseY >= knob.y && mouseY < knob.bottom
        heldAt = if (onTheKnob) mouseY - knob.y else KNOB_HEIGHT / 2.0
        return fractionAt(track, mouseY)
    }

    /** Where the knob is with the pointer at [mouseY], or null where it is not held. */
    fun drag(track: Rect, mouseY: Double): Double? = heldAt?.let { fractionAt(track, mouseY) }

    fun release() {
        heldAt = null
    }

    private fun fractionAt(track: Rect, mouseY: Double): Double {
        val travel = travelOf(track)
        if (travel <= 0) return 0.0
        val knobTop = mouseY - (heldAt ?: 0.0) - (track.y + INSET)
        return (knobTop / travel).coerceIn(0.0, 1.0)
    }

    private fun knobOn(track: Rect, fraction: Double): Rect {
        val down = (fraction.coerceIn(0.0, 1.0) * travelOf(track)).roundToInt()
        return Rect(track.x + INSET, track.y + INSET + down, track.width - 2 * INSET, KNOB_HEIGHT)
    }

    private fun travelOf(track: Rect): Int = track.height - 2 * INSET - KNOB_HEIGHT

    /** Dark above and left, light below and right: the game's way of saying something goes here. */
    private fun drawRecess(graphics: GuiGraphicsExtractor, at: Rect) {
        graphics.fill(at.x, at.y, at.right, at.bottom, Palette.HIGHLIGHT)
        graphics.fill(at.x, at.y, at.right - INSET, at.bottom - INSET, Palette.WELL_EDGE)
        graphics.fill(at.x + INSET, at.y + INSET, at.right - INSET, at.bottom - INSET, Palette.WELL)
    }

    /** Light above and left, dark below and right: a thing that can be pressed. */
    private fun drawKnob(graphics: GuiGraphicsExtractor, at: Rect) {
        graphics.fill(at.x, at.y, at.right, at.bottom, Palette.SHADOW)
        graphics.fill(at.x, at.y, at.right - BEVEL, at.bottom - BEVEL, Palette.HIGHLIGHT)
        graphics.fill(at.x + BEVEL, at.y + BEVEL, at.right - BEVEL, at.bottom - BEVEL, Palette.PANEL)
    }

    companion object {
        /** The track's width: half the stonecutter's twelve, with a pixel of recess either side of the knob. */
        const val WIDTH = 8

        /** The stonecutter's own knob height. */
        const val KNOB_HEIGHT = 15

        private const val INSET = 1
        private const val BEVEL = 1
    }
}

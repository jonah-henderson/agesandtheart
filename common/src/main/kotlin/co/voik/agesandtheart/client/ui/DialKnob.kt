package co.voik.agesandtheart.client.ui

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A round knob turned through [steps] notches: dragged up or right, scrolled, or stepped with the arrow
 * keys. It shows where it points and never a number.
 *
 * [turned] hears every notch the writer moves it to; [follow] sets it from outside without calling back,
 * and is ignored while the knob is held so the server's answer cannot snatch it out of the writer's hand.
 */
class DialKnob(
    size: Int,
    private val steps: Int,
    name: Component,
    private val turned: (Int) -> Unit,
) : AbstractWidget(0, 0, size, size, name) {

    var step: Int = 0
        private set

    private var held = false
    private var dragged = 0.0

    fun follow(outside: Int) {
        if (!held) step = outside.coerceIn(0, steps - 1)
    }

    private fun turnTo(wanted: Int) {
        val next = wanted.coerceIn(0, steps - 1)
        if (next == step) return
        step = next
        turned(next)
    }

    override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) {
        held = true
        dragged = 0.0
    }

    override fun onDrag(event: MouseButtonEvent, dx: Double, dy: Double) {
        dragged += dx - dy
        val notches = (dragged / PIXELS_A_NOTCH).toInt()
        if (notches == 0) return
        turnTo(step + notches)
        dragged -= notches * PIXELS_A_NOTCH
    }

    override fun onRelease(event: MouseButtonEvent) {
        held = false
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        if (!isMouseOver(x, y) || scrollY == 0.0) return false
        turnTo(step + sign(scrollY).toInt())
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val way = when {
            event.isRight() || event.isUp() -> 1
            event.isLeft() || event.isDown() -> -1
            else -> return false
        }
        turnTo(step + way)
        return true
    }

    override fun extractWidgetRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val radius = width / 2.0
        val centreX = x + radius
        val centreY = y + radius
        fillDisc(graphics, radius, if (isHoveredOrFocused) RIM_LIT else RIM)
        fillDisc(graphics, radius - 1, BODY)
        // Swept through three quarters of a turn, the dead quarter at the bottom, as a real dial is.
        val turn = if (steps <= 1) 0.0 else step.toDouble() / (steps - 1)
        val angle = (turn * SWEEP - SWEEP / 2) * PI / 180
        for (out in POINTER_FROM..(radius - 2).toInt()) {
            val pointX = (centreX + sin(angle) * out).toInt()
            val pointY = (centreY - cos(angle) * out).toInt()
            graphics.fill(pointX, pointY, pointX + 1, pointY + 1, POINTER)
        }
    }

    /** A disc of [radius] about the widget's middle, a row at a time. */
    private fun fillDisc(graphics: GuiGraphicsExtractor, radius: Double, colour: Int) {
        val middle = width / 2.0
        for (row in 0..<height) {
            val fromMiddle = row + 0.5 - middle
            val squared = radius * radius - fromMiddle * fromMiddle
            if (squared <= 0) continue
            val half = sqrt(squared).roundToInt()
            graphics.fill(x + (middle - half).toInt(), y + row, x + (middle + half).toInt(), y + row + 1, colour)
        }
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)

    private companion object {
        /** How far the pointer turns from one end to the other, in degrees. */
        const val SWEEP = 270.0

        /** How far a drag goes to move one notch. */
        const val PIXELS_A_NOTCH = 6.0

        /** Where the pointer starts out from the middle, leaving a hub. */
        const val POINTER_FROM = 2

        val RIM = Palette.OUTLINE
        val RIM_LIT = Palette.HIGHLIGHT
        val BODY = 0xFF3A3A3A.toInt()
        val POINTER = 0xFFE8E8E8.toInt()
    }
}

package co.voik.agesandtheart.client.ui

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.CommonComponents
import net.minecraft.resources.Identifier

/**
 * A furnace's arrow, filling left to right as [reading] goes from nothing to done.
 *
 * Vanilla ships only the filled arrow as a sprite, the empty one being part of the furnace's background,
 * so the empty track is the same sprite drawn dim.
 */
class ProgressArrow(x: Int, y: Int, private val reading: () -> Float) :
    AbstractWidget(x, y, WIDTH, HEIGHT, CommonComponents.EMPTY) {

    override fun extractWidgetRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SPRITE, x, y, width, height, EMPTY_TRACK)
        val filled = (width * reading().coerceIn(0f, 1f)).toInt()
        if (filled > 0) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SPRITE, width, height, 0, 0, x, y, filled, height)
        }
    }

    override fun isMouseOver(mouseX: Double, mouseY: Double): Boolean = false

    override fun updateWidgetNarration(output: NarrationElementOutput) = Unit

    private companion object {
        val SPRITE: Identifier = Identifier.withDefaultNamespace("container/furnace/burn_progress")
        const val WIDTH = 24
        const val HEIGHT = 16

        /** The sprite at a quarter strength, near enough the furnace's own grey track. */
        const val EMPTY_TRACK = 0x40FFFFFF
    }
}

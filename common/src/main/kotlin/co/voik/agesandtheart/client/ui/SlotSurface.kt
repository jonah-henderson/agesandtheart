package co.voik.agesandtheart.client.ui

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier

/**
 * An empty slot's recess — vanilla's own `container/slot` sprite, which is why it matches every other slot
 * in the game exactly.
 *
 * [at] is the whole 18×18 frame, not the 16×16 item area inside it. [SlotView] handles that difference so
 * callers can go on thinking in the item coordinates the menu uses.
 */
object SlotSurface : Decoration {
    private val SPRITE: Identifier = Identifier.withDefaultNamespace("container/slot")

    override fun draw(graphics: GuiGraphicsExtractor, at: Rect) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SPRITE, at.x, at.y, at.width, at.height)
    }
}

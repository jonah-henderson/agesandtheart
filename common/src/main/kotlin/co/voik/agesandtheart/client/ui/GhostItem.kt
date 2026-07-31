package co.voik.agesandtheart.client.ui

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

/**
 * What belongs in an empty slot, shown as a hint rather than a thing.
 *
 * The real item rather than a GUI sprite of one, since item textures live on another atlas — and drawing
 * the actual item means a resource pack restyling it restyles this too.
 *
 * [options] returning several cycles between them, the way a recipe viewer cycles a tag, so a slot that
 * accepts a category shows what the category holds rather than always promising one member of it. Returning
 * nothing draws nothing, which is how a filled slot hides its own hint.
 */
class GhostItem(
    private val options: () -> List<ItemStack>,
) : AbstractWidget(0, 0, Palette.ITEM, Palette.ITEM, Component.empty()) {

    override fun extractWidgetRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        a: Float,
    ) {
        val showing = options()
        if (showing.isEmpty()) return
        val turn = ((System.currentTimeMillis() / CYCLE_MS) % showing.size).toInt()
        // Vanilla's own ghosting, values and order included: darken, draw, wash out.
        graphics.fill(x, y, x + width, y + height, Palette.GHOST_UNDER)
        graphics.fakeItem(showing[turn], x, y)
        graphics.fill(x, y, x + width, y + height, Palette.GHOST_OVER)
    }

    /** Places the hint over the item area at [itemX], [itemY]. */
    fun coverItemAt(itemX: Int, itemY: Int) = setPosition(itemX, itemY)

    override fun isMouseOver(mouseX: Double, mouseY: Double): Boolean = false

    override fun updateWidgetNarration(output: NarrationElementOutput) = Unit

    private companion object {
        const val CYCLE_MS = 1000L
    }
}

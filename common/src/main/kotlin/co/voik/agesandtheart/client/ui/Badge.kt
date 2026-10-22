package co.voik.agesandtheart.client.ui

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

/** A small item drawn beside a word to say something about it, and what hovering it says. */
data class Badge(val icon: ItemStack, val tooltip: Component) {

    /** Draws the icon with its top left at [x], [y], [SIZE] across. */
    fun draw(graphics: GuiGraphicsExtractor, x: Int, y: Int) {
        graphics.pose().pushMatrix()
        graphics.pose().translate(x.toFloat(), y.toFloat())
        graphics.pose().scale(SCALE, SCALE)
        graphics.fakeItem(icon, 0, 0)
        graphics.pose().popMatrix()
    }

    /** Whether the pointer at [mouseX], [mouseY] is over one drawn at [x], [y]. */
    fun isUnder(mouseX: Int, mouseY: Int, x: Int, y: Int): Boolean =
        mouseX >= x && mouseX < x + SIZE && mouseY >= y && mouseY < y + SIZE

    companion object {
        /** Half an item, which is as much as a row of text has room for. */
        const val SIZE = 8
        private const val SCALE = 0.5f
    }
}

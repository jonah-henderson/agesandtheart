package co.voik.agesandtheart.client.ui

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.InputWithModifiers
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

/**
 * A vanilla button with an item drawn on it in place of a label — an icon until there are sprites to draw
 * one with. [name] is read aloud and belongs in its tooltip too.
 */
class ItemIconButton(
    size: Int,
    name: Component,
    private val icon: () -> ItemStack,
    private val pressed: () -> Unit,
) : AbstractButton(0, 0, size, size, name) {

    override fun onPress(input: InputWithModifiers) = pressed()

    override fun extractContents(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        extractDefaultSprite(graphics)
        graphics.item(icon(), x + (width - Palette.ITEM) / 2, y + (height - Palette.ITEM) / 2)
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)
}

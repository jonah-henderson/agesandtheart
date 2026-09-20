package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.word.WordNames
import co.voik.agesandtheart.client.ui.ParchmentSurface
import co.voik.agesandtheart.client.ui.Rect
import co.voik.agesandtheart.content.AgeComponents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack

/**
 * One page, held up and read: the word in the script, and underneath it the same word in a language the
 * player has.
 *
 * Nothing here decides anything — the word was rolled when the page was made and learnt when it was
 * picked up. This is what a page is *for* as an object.
 */
class PageScreen(private val word: Identifier) : Screen(titleFor(word)) {

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, a)

        val left = (width - PAGE_WIDTH) / 2
        val top = (height - PAGE_HEIGHT) / 2
        ParchmentSurface.draw(graphics, Rect(left, top, PAGE_WIDTH, PAGE_HEIGHT))

        val middle = width / 2
        // Drawn at the origin and moved by the transform: `centeredText` centres on the coordinate it is
        // given, and a scaled coordinate would put the glyphs somewhere else entirely.
        val script = KnownWords.scriptText(word)
        graphics.pose().pushMatrix()
        graphics.pose().translate(middle.toFloat(), (top + SCRIPT_BASELINE).toFloat())
        graphics.pose().scale(scriptScale(script), scriptScale(script))
        centeredNoShadow(graphics, script, 0, 0, ParchmentSurface.INK)
        graphics.pose().popMatrix()

        centeredNoShadow(graphics, WordNames.readable(word), middle, top + NAME_BASELINE, ParchmentSurface.FAINT_INK)
    }

    /**
     * Big, but never wider than the page.
     *
     * A fixed multiplier is fine until a long word arrives — `floating` already ran off both edges at
     * three times. This takes the largest scale that still fits the writing area, so the glyphs are as
     * large as they can be rather than as large as the shortest word allowed.
     */
    private fun scriptScale(text: Component): Float {
        val drawn = font.width(text.visualOrderText).toFloat()
        if (drawn <= 0f) return MAX_SCRIPT_SCALE
        return kotlin.math.min(MAX_SCRIPT_SCALE, (PAGE_WIDTH - WRITING_MARGIN) / drawn)
    }

    /**
     * Centred text with no drop shadow.
     *
     * `centeredText` has no shadow flag, so the centring is done here and the `text` overload that does
     * is called directly. Ink on a page does not cast a shadow.
     */
    private fun centeredNoShadow(graphics: GuiGraphicsExtractor, text: Component, x: Int, y: Int, colour: Int) {
        val ordered = text.visualOrderText
        graphics.text(font, ordered, x - font.width(ordered) / 2, y, colour, false)
    }

    companion object {
        /** Quoted, because the title names the word rather than describing it. */
        private fun titleFor(word: Identifier): Component =
            Component.translatable("screen.agesandtheart.page", WordNames.readable(word))

        private const val PAGE_WIDTH = 148
        private const val PAGE_HEIGHT = 180
        private const val SCRIPT_BASELINE = 70
        private const val NAME_BASELINE = 128
        /** As large as a short word may go; longer ones shrink to fit. */
        private const val MAX_SCRIPT_SCALE = 2.5f

        /** Clear space either side of the script, so it never touches the border. */
        private const val WRITING_MARGIN = 24f

        /** Opens the page in [stack], or does nothing if it is blank. Client-side only. */
        fun open(stack: ItemStack) {
            val word = stack.get(AgeComponents.PAGE_WORD) ?: return
            Minecraft.getInstance().setScreenAndShow(PageScreen(word))
        }
    }
}

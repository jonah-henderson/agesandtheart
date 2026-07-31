package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.word.WordNames
import co.voik.agesandtheart.content.AgeContent
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack

/**
 * One page, held up and read: the word in the script, and underneath it the same word in a language the
 * player has.
 *
 * Nothing here decides anything — the word was rolled when the page was made and learnt when it was
 * picked up. This is what a page is *for* as an object.
 */
class PageScreen(private val word: Identifier) : Screen(WordNames.readable(word)) {

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, a)

        val left = (width - PAGE_WIDTH) / 2
        val top = (height - PAGE_HEIGHT) / 2
        graphics.fill(left, top, left + PAGE_WIDTH, top + PAGE_HEIGHT, EDGE)
        graphics.fill(left + 1, top + 1, left + PAGE_WIDTH - 1, top + PAGE_HEIGHT - 1, PARCHMENT)

        val middle = width / 2
        // Drawn at the origin and moved by the transform: `centeredText` centres on the coordinate it is
        // given, and a scaled coordinate would put the glyphs somewhere else entirely.
        graphics.pose().pushMatrix()
        graphics.pose().translate(middle.toFloat(), (top + SCRIPT_BASELINE).toFloat())
        graphics.pose().scale(SCRIPT_SCALE, SCRIPT_SCALE)
        graphics.centeredText(font, KnownWords.scriptText(word), 0, 0, INK)
        graphics.pose().popMatrix()

        graphics.centeredText(font, WordNames.readable(word), middle, top + NAME_BASELINE, FAINT_INK)
    }

    companion object {
        private const val PAGE_WIDTH = 148
        private const val PAGE_HEIGHT = 180
        private const val SCRIPT_BASELINE = 70
        private const val NAME_BASELINE = 128
        private const val SCRIPT_SCALE = 3.0f

        private val PARCHMENT = 0xFFE9DFC3.toInt()
        private val EDGE = 0xFF8B7B55.toInt()
        private val INK = 0xFF2B2118.toInt()
        private val FAINT_INK = 0xFF6B5C46.toInt()

        /** Opens the page in [stack], or does nothing if it is blank. Client-side only. */
        fun open(stack: ItemStack) {
            val word = stack.get(AgeContent.PAGE_WORD) ?: return
            Minecraft.getInstance().setScreen(PageScreen(word))
        }
    }
}

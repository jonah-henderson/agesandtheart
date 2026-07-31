package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.word.WordNames
import co.voik.agesandtheart.book.LinkRequest
import co.voik.agesandtheart.content.AgeContent
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack

/** Opens the book, kept apart so the item never names a client class directly. */
object BookScreenOpener {
    fun open(stack: ItemStack, hand: InteractionHand) {
        Minecraft.getInstance().setScreen(BookScreen(stack, hand))
    }
}

/**
 * A Descriptive Book, held open.
 *
 * Two pages, as the source has them: the **linking panel** on the left and the **sentence** on the
 * right. Clicking the panel goes; nothing else does, because linking spends the book and can strand you.
 *
 * The panel is flat for now. It wants to be a view of the Age's spawn — computable from the chunk
 * generator rather than rendered, since a client that has never been there has no chunks — but that is
 * later work and a black panel is honest in the meantime.
 */
class BookScreen(
    private val book: ItemStack,
    private val hand: InteractionHand,
) : Screen(book.hoverName) {

    private val words: List<Identifier> get() = book.get(AgeContent.BOOK_WORDS).orEmpty()

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, a)
        val left = (width - WIDTH) / 2
        val top = (height - HEIGHT) / 2

        graphics.fill(left, top, left + WIDTH, top + HEIGHT, EDGE)
        graphics.fill(left + 1, top + 1, left + WIDTH - 1, top + HEIGHT - 1, PARCHMENT)
        // The spine, so it reads as two pages rather than one sheet.
        graphics.fill(left + WIDTH / 2 - 1, top + 1, left + WIDTH / 2 + 1, top + HEIGHT - 1, EDGE)

        drawPanel(graphics, left, top, mouseX, mouseY)
        drawSentence(graphics, left, top)
    }

    private fun drawPanel(graphics: GuiGraphicsExtractor, left: Int, top: Int, mouseX: Int, mouseY: Int) {
        val x = left + PANEL_X
        val y = top + PANEL_Y
        graphics.fill(x - 1, y - 1, x + PANEL_WIDTH + 1, y + PANEL_HEIGHT + 1, EDGE)
        graphics.fill(x, y, x + PANEL_WIDTH, y + PANEL_HEIGHT, PANEL)
        if (overPanel(mouseX.toDouble(), mouseY.toDouble())) {
            graphics.fill(x, y, x + PANEL_WIDTH, y + PANEL_HEIGHT, PANEL_LIT)
        }
    }

    /**
     * The sentence, set as running text.
     *
     * The script runs on and wraps; the reading follows underneath in fainter ink.
     *
     * Only the pages actually in the book for now. The inferred particles the readout adds — the `of`,
     * `over`, `with` a writer was spared — are the next piece, and they want the grammar settled first.
     */
    private fun drawSentence(graphics: GuiGraphicsExtractor, left: Int, top: Int) {
        val x = left + TEXT_X
        graphics.text(font, book.hoverName, x, top + TITLE_Y, INK, false)
        if (words.isEmpty()) return

        val written = KnownWords.scriptText(words)
        var y = top + TEXT_Y
        y = flow(graphics, written, x, y, INK)

        y += PARAGRAPH_GAP
        val read = words.joinToString(" ") { WordNames.readable(it).string }
        flow(graphics, Component.literal(read), x, y, FAINT_INK)
    }

    /** Wraps [text] into the page's column and returns the line after it. */
    private fun flow(
        graphics: GuiGraphicsExtractor,
        text: Component,
        x: Int,
        top: Int,
        colour: Int,
    ): Int {
        var y = top
        font.split(text, COLUMN_WIDTH).forEach { line ->
            graphics.text(font, line, x, y, colour, false)
            y += LINE
        }
        return y
    }

    private fun overPanel(mouseX: Double, mouseY: Double): Boolean {
        val x = (width - WIDTH) / 2 + PANEL_X
        val y = (height - HEIGHT) / 2 + PANEL_Y
        return mouseX >= x && mouseX <= x + PANEL_WIDTH && mouseY >= y && mouseY <= y + PANEL_HEIGHT
    }

    override fun mouseClicked(event: net.minecraft.client.input.MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (overPanel(event.x, event.y)) {
            ClientDeskNetwork.sender?.invoke(LinkRequest(hand))
            onClose()
            return true
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun isPauseScreen(): Boolean = false

    private companion object {
        const val WIDTH = 256
        const val HEIGHT = 180

        const val PANEL_X = 18
        const val PANEL_Y = 30
        const val PANEL_WIDTH = 92
        const val PANEL_HEIGHT = 92

        const val TEXT_X = 140
        const val TITLE_Y = 16
        const val TEXT_Y = 34
        const val LINE = 10

        /** The right page's writing column, clear of the spine and the outer edge. */
        const val COLUMN_WIDTH = WIDTH / 2 - 30
        const val PARAGRAPH_GAP = 8

        val PARCHMENT = 0xFFE9DFC3.toInt()
        val EDGE = 0xFF8B7B55.toInt()
        val INK = 0xFF2B2118.toInt()
        val FAINT_INK = 0xFF6B5C46.toInt()

        /** Black until it can show the Age. */
        val PANEL = 0xFF07070C.toInt()
        val PANEL_LIT = 0x18FFFFFF
    }
}

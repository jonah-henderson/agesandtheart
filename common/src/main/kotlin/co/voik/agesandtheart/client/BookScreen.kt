package co.voik.agesandtheart.client

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
     * The sentence, set as running text: **the script as the writer laid it, and what it says underneath
     * in fainter ink.**
     *
     * The two are not glossed word against word, and that is the point. A reading is prose — it carries the
     * particles a writer was spared for being inferable from position (`of`, `over`, `with`) and the
     * punctuation the sections never had — so there is nothing above an `of` to align it to. Pairing them
     * up would mean either dropping the particles, which is what the row of pages already shows, or hanging
     * them under a word they are not a translation of.
     */
    private fun drawSentence(graphics: GuiGraphicsExtractor, left: Int, top: Int) {
        val x = left + TEXT_X
        scaled(graphics, x, top + TITLE_Y, TITLE_SCALE) {
            graphics.text(font, book.hoverName, 0, 0, INK, false)
        }
        if (words.isEmpty()) return

        val below = wrapped(graphics, KnownWords.scriptText(words), x, top + TEXT_Y, SCRIPT_SCALE, INK)
        val reading = book.get(AgeContent.BOOK_READING) ?: return
        wrapped(graphics, reading, x, below + PHRASE_GAP, READING_SCALE, FAINT_INK)
    }

    /** [text] wrapped to the writing column and drawn at [scale], answering with the y below it. */
    private fun wrapped(
        graphics: GuiGraphicsExtractor,
        text: Component,
        x: Int,
        y: Int,
        scale: Float,
        colour: Int,
    ): Int {
        // The column is measured in screen pixels and the font in its own, so the width it is asked to
        // wrap at has to be the column *at this scale* — otherwise small text wraps as though it were big.
        val column = (COLUMN_WIDTH / scale).toInt()
        val lineHeight = (font.lineHeight * scale).toInt() + 1
        var line = y
        for (row in font.split(text, column)) {
            scaled(graphics, x, line, scale) { graphics.text(font, row, 0, 0, colour, false) }
            line += lineHeight
        }
        return line
    }

    /** Draws [body] at [scale] with the origin moved to ([x], [y]), since text is placed by its corner. */
    private fun scaled(graphics: GuiGraphicsExtractor, x: Int, y: Int, scale: Float, body: () -> Unit) {
        graphics.pose().pushMatrix()
        graphics.pose().translate(x.toFloat(), y.toFloat())
        graphics.pose().scale(scale, scale)
        body()
        graphics.pose().popMatrix()
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

        /** The right page's writing column, clear of the spine and the outer edge. */
        const val COLUMN_WIDTH = WIDTH / 2 - 30

        /** Title, script, reading — each a step down, so the hierarchy is the size. */
        const val TITLE_SCALE = 1.15f
        const val SCRIPT_SCALE = 0.9f
        const val READING_SCALE = 0.7f

        /** Between the script and the reading under it, so the two read as a pair and not a block. */
        const val PHRASE_GAP = 5

        val PARCHMENT = 0xFFE9DFC3.toInt()
        val EDGE = 0xFF8B7B55.toInt()
        val INK = 0xFF2B2118.toInt()
        val FAINT_INK = 0xFF6B5C46.toInt()

        /** Black until it can show the Age. */
        val PANEL = 0xFF07070C.toInt()
        val PANEL_LIT = 0x18FFFFFF
    }
}

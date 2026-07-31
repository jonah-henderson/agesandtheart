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
        scaled(graphics, x, top + TITLE_Y, TITLE_SCALE) {
            graphics.text(font, book.hoverName, 0, 0, INK, false)
        }
        if (words.isEmpty()) return

        var y = top + TEXT_Y
        linesOf().forEach { line ->
            var column = x
            line.forEach { gloss ->
                scaled(graphics, column, y, SCRIPT_SCALE) {
                    graphics.text(font, gloss.script, 0, 0, INK, false)
                }
                scaled(graphics, column, y + scriptHeight(), READING_SCALE) {
                    graphics.text(font, gloss.reading, 0, 0, FAINT_INK, false)
                }
                column += gloss.width
            }
            y += scriptHeight() + readingHeight() + PHRASE_GAP
        }
    }

    private fun scriptHeight() = (font.lineHeight * SCRIPT_SCALE).toInt() + 1

    private fun readingHeight() = (font.lineHeight * READING_SCALE).toInt()

    /**
     * One word set over its own reading.
     *
     * [width] is the wider of the two at their own scales, so the pair occupies a column and the next pair
     * starts clear of it — which is what keeps a reading under the phrase it belongs to rather than under
     * whatever happens to be above it.
     */
    private inner class Gloss(val script: Component, val reading: String) {
        val width: Int = maxOf(
            (font.width(script) * SCRIPT_SCALE).toInt(),
            (font.width(reading) * READING_SCALE).toInt(),
        ) + GLOSS_GAP
    }

    /**
     * A word split into its parts, each part glossed on its own.
     *
     * A derived word is a block id, so `polished_deepslate` is two words wearing one name — and glossing
     * it whole puts "Polished Deepslate" under a script that plainly has two pieces. The transliteration
     * already renders `_` as a space, so both sides divide the same way.
     *
     * Where they do not divide alike — an authored spelling need not follow the id — the word is glossed
     * whole rather than paired up wrongly.
     */
    private fun glossesOf(word: Identifier): List<Gloss> {
        val script = KnownWords.scriptLines(word)
        val reading = WordNames.readable(word).string.split(' ').filter { it.isNotBlank() }
        if (script.size != reading.size || script.isEmpty()) {
            return listOf(Gloss(KnownWords.scriptText(word), reading.joinToString(" ")))
        }
        return script.indices.map { Gloss(script[it], reading[it]) }
    }

    /** The glosses packed into lines that fit the column. */
    private fun linesOf(): List<List<Gloss>> {
        val lines = mutableListOf<List<Gloss>>()
        var line = mutableListOf<Gloss>()
        var used = 0
        words.flatMap(::glossesOf).forEach { gloss ->
            if (line.isNotEmpty() && used + gloss.width > COLUMN_WIDTH) {
                lines += line
                line = mutableListOf()
                used = 0
            }
            line += gloss
            used += gloss.width
        }
        if (line.isNotEmpty()) lines += line
        return lines
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
        const val LINE = 10

        /** The right page's writing column, clear of the spine and the outer edge. */
        const val COLUMN_WIDTH = WIDTH / 2 - 30

        /** Title, script, reading — each a step down, so the hierarchy is the size. */
        const val TITLE_SCALE = 1.15f
        const val SCRIPT_SCALE = 0.9f
        const val READING_SCALE = 0.7f

        /** Between one line of glosses and the next. */
        const val PHRASE_GAP = 5

        /** Clear space after a gloss, so adjacent columns do not read as one word. */
        const val GLOSS_GAP = 4

        val PARCHMENT = 0xFFE9DFC3.toInt()
        val EDGE = 0xFF8B7B55.toInt()
        val INK = 0xFF2B2118.toInt()
        val FAINT_INK = 0xFF6B5C46.toInt()

        /** Black until it can show the Age. */
        val PANEL = 0xFF07070C.toInt()
        val PANEL_LIT = 0x18FFFFFF
    }
}

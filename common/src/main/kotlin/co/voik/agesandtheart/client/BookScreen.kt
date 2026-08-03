package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.LinkRequest
import co.voik.agesandtheart.content.AgeContent
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack

/** Opens the book, kept apart so the item never names a client class directly. */
object BookScreenOpener {
    fun open(stack: ItemStack, hand: InteractionHand) {
        Minecraft.getInstance().setScreen(BookScreen(stack, hand))
    }
}

/**
 * A Descriptive Book, held open at one spread.
 *
 * **The first spread is the linking panel and the opening page of writing**; every spread after it is two
 * pages of writing. Clicking the panel goes — nothing else does, because linking spends the book and can
 * strand you — and clicking a page turns it: the right page forward, the left page back.
 *
 * The panel is flat for now. It wants to be a view of the Age's spawn — computable from the chunk
 * generator rather than rendered, since a client that has never been there has no chunks — but that is
 * later work and a black panel is honest in the meantime.
 */
class BookScreen(
    private val book: ItemStack,
    private val hand: InteractionHand,
) : Screen(book.hoverName) {

    /** Which spread is open. Nought is the panel and the first page of writing. */
    private var spread = 0

    /**
     * The writing, wrapped and cut into pages. Paginated once: the font is fixed, the column is fixed, and
     * doing it per frame would re-wrap the whole book sixty times a second.
     */
    private val pages: List<List<Row>> by lazy { paginate() }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, a)
        val left = (width - WIDTH) / 2
        val top = (height - HEIGHT) / 2

        graphics.fill(left, top, left + WIDTH, top + HEIGHT, EDGE)
        graphics.fill(left + 1, top + 1, left + WIDTH - 1, top + HEIGHT - 1, PARCHMENT)
        // The spine, so it reads as two pages rather than one sheet.
        graphics.fill(left + WIDTH / 2 - 1, top + 1, left + WIDTH / 2 + 1, top + HEIGHT - 1, EDGE)

        if (spread == 0) {
            drawPanel(graphics, left, top, mouseX, mouseY)
            scaled(graphics, left + RIGHT_COLUMN_X, top + TITLE_Y, TITLE_SCALE) {
                graphics.text(font, book.hoverName, 0, 0, INK, false)
            }
        } else {
            drawPage(graphics, left + LEFT_COLUMN_X, top, leftPageOf(spread))
        }
        drawPage(graphics, left + RIGHT_COLUMN_X, top, rightPageOf(spread))
        drawTurningCorners(graphics, left, top, mouseX, mouseY)
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

    /** One page of writing, or nothing where the book has no such page. */
    private fun drawPage(graphics: GuiGraphicsExtractor, x: Int, top: Int, at: Int) {
        val page = pages.getOrNull(at) ?: return
        var y = top + writingBeginsOn(at)
        for (row in page) {
            val line = row.text
            if (line != null) {
                scaled(graphics, x, y, row.scale) { graphics.text(font, line, 0, 0, row.colour, false) }
            }
            y += row.height
        }
    }

    /**
     * The outer corner of a page there is somewhere to turn to, lit under the pointer.
     *
     * A corner rather than the whole page: the page is what you click, but lighting all of it under the
     * writing reads as a selection rather than as a page about to lift.
     */
    private fun drawTurningCorners(graphics: GuiGraphicsExtractor, left: Int, top: Int, mouseX: Int, mouseY: Int) {
        val bottom = top + HEIGHT - 1
        if (canTurnForward() && overRightPage(mouseX.toDouble(), mouseY.toDouble())) {
            val right = left + WIDTH - 1
            graphics.fill(right - TURNING_CORNER, bottom - TURNING_CORNER, right, bottom, PANEL_LIT)
        }
        if (spread > 0 && overLeftPage(mouseX.toDouble(), mouseY.toDouble())) {
            graphics.fill(left + 1, bottom - TURNING_CORNER, left + 1 + TURNING_CORNER, bottom, PANEL_LIT)
        }
    }

    /**
     * The whole book as wrapped lines: **the sentence as the Art writes it, then what it says.**
     *
     * Both blocks whole rather than clause against clause, so a page break may fall anywhere — the two are
     * one sentence written twice, and nothing has to stay level with anything. A long book therefore reads
     * as pages of writing followed by pages of reading, which is what a translation *is*.
     */
    private fun rowsOfWriting(): List<Row> {
        val said = book.get(AgeContent.BOOK_TEXT).orEmpty()
        if (said.isEmpty()) return emptyList()
        val script = linesOf(KnownWords.scriptLine(said), SCRIPT_SCALE, INK)
        val reading = book.get(AgeContent.BOOK_READING) ?: return script
        return script + Row(null, SCRIPT_SCALE, INK, PHRASE_GAP) + linesOf(reading, READING_SCALE, FAINT_INK)
    }

    private fun linesOf(text: Component, scale: Float, colour: Int): List<Row> {
        // The column is measured in screen pixels and the font in its own, so the width it is asked to
        // wrap at has to be the column *at this scale* — otherwise small text wraps as though it were big.
        val height = (font.lineHeight * scale).toInt() + 1
        return font.split(text, (COLUMN_WIDTH / scale).toInt()).map { Row(it, scale, colour, height) }
    }

    /** The lines cut into pages, greedily, each page taking what its own height allows. */
    private fun paginate(): List<List<Row>> {
        val cut = mutableListOf<List<Row>>()
        var page = mutableListOf<Row>()
        var used = 0
        for (row in rowsOfWriting()) {
            if (page.isNotEmpty() && used + row.height > roomOn(cut.size)) {
                cut += page
                page = mutableListOf()
                used = 0
            }
            page += row
            used += row.height
        }
        if (page.isNotEmpty()) cut += page
        return cut
    }

    /** Where writing starts down a page — the first one begins under the title, the rest at the top. */
    private fun writingBeginsOn(page: Int): Int = if (page == 0) TEXT_Y else TOP_MARGIN

    private fun roomOn(page: Int): Int = HEIGHT - writingBeginsOn(page) - BOTTOM_MARGIN

    /** Which page of writing sits where, given a spread. The first spread's left leaf is the panel. */
    private fun leftPageOf(spread: Int): Int = 2 * spread - 1

    private fun rightPageOf(spread: Int): Int = 2 * spread

    private fun canTurnForward(): Boolean = leftPageOf(spread + 1) <= pages.lastIndex

    /** One wrapped line and how it is set. A null [text] is the space between the writing and the reading. */
    private class Row(
        val text: FormattedCharSequence?,
        val scale: Float,
        val colour: Int,
        val height: Int,
    )

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

    private fun overLeftPage(mouseX: Double, mouseY: Double): Boolean =
        overLeaf(mouseX, mouseY, from = 1, to = WIDTH / 2 - 1)

    private fun overRightPage(mouseX: Double, mouseY: Double): Boolean =
        overLeaf(mouseX, mouseY, from = WIDTH / 2 + 1, to = WIDTH - 1)

    private fun overLeaf(mouseX: Double, mouseY: Double, from: Int, to: Int): Boolean {
        val left = (width - WIDTH) / 2
        val top = (height - HEIGHT) / 2
        val withinTheLeaf = mouseX >= left + from && mouseX <= left + to
        return withinTheLeaf && mouseY >= top + 1 && mouseY <= top + HEIGHT - 1
    }

    /**
     * **The panel is the only thing that links**, and it is asked first: a click that turned a page instead
     * would be a click that failed to strand you, but one that linked instead of turning is a book spent.
     */
    override fun mouseClicked(event: net.minecraft.client.input.MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (spread == 0 && overPanel(event.x, event.y)) {
            ClientDeskNetwork.sender?.invoke(LinkRequest(hand))
            onClose()
            return true
        }
        if (canTurnForward() && overRightPage(event.x, event.y)) {
            spread++
            return true
        }
        if (spread > 0 && overLeftPage(event.x, event.y)) {
            spread--
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

        /** Where each leaf's writing column begins, clear of the spine and the outer edge. */
        const val LEFT_COLUMN_X = 18
        const val RIGHT_COLUMN_X = 140

        const val TITLE_Y = 16

        /** Where writing starts down a page: under the title on the first, and at the top after it. */
        const val TEXT_Y = 34
        const val TOP_MARGIN = 16
        const val BOTTOM_MARGIN = 14

        /** The writing column itself. */
        const val COLUMN_WIDTH = WIDTH / 2 - 30

        /** The corner that lights when a page has somewhere to turn to. */
        const val TURNING_CORNER = 14

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

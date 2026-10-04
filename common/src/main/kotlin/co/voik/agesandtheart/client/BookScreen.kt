package co.voik.agesandtheart.client

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.word.grammar.ProseWriting
import co.voik.agesandtheart.book.BookBeingRead
import co.voik.agesandtheart.book.BookPage
import co.voik.agesandtheart.book.LecternBooks
import co.voik.agesandtheart.book.LinkRequest
import co.voik.agesandtheart.client.panel.LecternPanels
import co.voik.agesandtheart.client.panel.LinkingPanel
import co.voik.agesandtheart.client.panel.PanelComposite
import co.voik.agesandtheart.client.panel.PanelPicture
import co.voik.agesandtheart.client.panel.PanelTarget
import co.voik.agesandtheart.client.ui.ParchmentSurface
import co.voik.agesandtheart.client.ui.Rect
import co.voik.agesandtheart.content.AgeComponents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack

/** Opens the book, kept apart so the item never names a client class directly. */
object BookScreenOpener {
    fun open(stack: ItemStack, hand: InteractionHand) {
        Minecraft.getInstance().setScreenAndShow(BookScreen(stack, BookBeingRead.InHand(hand)))
    }

    fun openFromLectern(stack: ItemStack, pos: BlockPos) {
        Minecraft.getInstance().setScreenAndShow(BookScreen(stack, BookBeingRead.OnALectern(pos)))
    }
}

/**
 * A book of ours, held open at one spread.
 *
 * A descriptive book's first spread is the linking panel and the opening page of writing; every spread after
 * it is two pages of writing. A linking book is one spread with its panel on the right and nothing facing it.
 * Clicking the panel goes — nothing else does, because linking spends the book and can strand you — and clicking
 * a page turns it: the right page forward, the left page back.
 *
 * The panel asks for its Age while the book is open and gives it back when it closes (design §7.8.1), which
 * is what makes a live panel affordable at all. **The screen always has a live panel, and outranks every
 * lectern** (§7.8.2): it covers most of the world, so the panel being looked at is the one that has to be
 * current. A book read off a lectern takes up that lectern's panel, and the lectern goes on showing the same
 * picture.
 */
class BookScreen(
    private val book: ItemStack,
    val held: BookBeingRead,
) : Screen(book.hoverName) {

    /** Whether clicking the panel links: only in a hand, since a lectern's book links by a click on the lectern. */
    private val linksFromThePanel = held is BookBeingRead.InHand

    /** Which leaf the panel is on: a descriptive book's left, beside its writing, and a linking book's right. */
    private val panelPage = LecternBooks.panelPageOf(book) ?: BookPage.LEFT

    /** Which spread is open. Nought is the panel and the first page of writing. */
    private var spread = 0

    /** When this book was opened, which is what the panel's wait is measured against. */
    private val openedAt = System.nanoTime()

    /**
     * The writing, wrapped and cut into pages. Paginated once: the font is fixed, the column is fixed, and
     * doing it per frame would re-wrap the whole book sixty times a second.
     */
    private val pages: List<List<Line>> by lazy { paginate() }

    /** Asks for the Age as the screen opens, so the ring is already arriving by the first frame. */
    override fun init() {
        super.init()
        // Learned locally on this frame rather than after the server has rolled an Age (design §4.5). The
        // server still teaches authoritatively, and its payload adds nothing when it lands.
        KnownWords.readFrom(book.get(AgeComponents.BOOK_WORDS).orEmpty())
        LinkingPanel.ask(held)
    }

    /** Whether the panel's spread is the one open, which is when this screen composes the panel's picture. */
    val isShowingItsPanel: Boolean get() = spread == 0

    /** The panel's tick while the screen is up: its camera's environment probe, and the wait for an answer. */
    override fun tick() {
        super.tick()
        LinkingPanel.tick()
    }

    /**
     * Gives a hand's ring back, on every way out rather than only the link path.
     *
     * A lectern's is handed back to [LecternPanels] instead, which keeps it while that lectern is still the one
     * to show, so closing the screen does not load the panel again from mist.
     */
    override fun removed() {
        super.removed()
        if (held is BookBeingRead.InHand) LinkingPanel.release()
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, a)
        val left = (width - WIDTH) / 2
        val top = (height - HEIGHT) / 2

        ParchmentSurface.draw(graphics, Rect(left, top, WIDTH, HEIGHT))
        // The spine, so it reads as two pages rather than one sheet.
        graphics.fill(left + WIDTH / 2 - 1, top + 1, left + WIDTH / 2 + 1, top + HEIGHT - 1, ParchmentSurface.EDGE)

        if (spread == 0) {
            drawPanel(graphics, left, top, mouseX, mouseY)
            // A linking book's panel has the right leaf to itself and nothing faces it: the book is a door to
            // a place, and has no words to show (design §7.8.2).
            if (panelPage == BookPage.LEFT) {
                scaled(graphics, left + RIGHT_COLUMN_X, top + TITLE_Y, TITLE_SCALE) {
                    graphics.text(font, book.hoverName, 0, 0, ParchmentSurface.INK, false)
                }
            }
        } else {
            drawPage(graphics, left + LEFT_COLUMN_X, top, leftPageOf(spread))
        }
        drawPage(graphics, left + RIGHT_COLUMN_X, top, rightPageOf(spread))
        drawTurningCorners(graphics, left, top, mouseX, mouseY)
    }

    /**
     * The panel: one blit of its finished picture, frame and all — the picture a lectern's book shows too
     * (design §7.8.2) — and what a wait adds to it, and a pointer where clicking it links.
     */
    private fun drawPanel(graphics: GuiGraphicsExtractor, left: Int, top: Int, mouseX: Int, mouseY: Int) {
        val x = left + panelAcrossTheBook()
        val y = top + PANEL_Y
        val picture = PanelComposite.composeLive(LinkingPanel.preview, Minecraft.getInstance().deltaTracker)
        val frame = PanelPicture.FRAME_WIDTH
        // V backwards: a render target's origin is at its bottom.
        graphics.blit(
            picture,
            PanelTarget.sampler(),
            x - frame,
            y - frame,
            x + PanelPicture.WIDTH + frame,
            y + PanelPicture.HEIGHT + frame,
            0.0f,
            1.0f,
            1.0f,
            0.0f,
        )
        if (linksFromThePanel && overPanel(mouseX.toDouble(), mouseY.toDouble())) {
            graphics.fill(x, y, x + PanelPicture.WIDTH, y + PanelPicture.HEIGHT, PANEL_LIT)
        }
        drawWaiting(graphics, x, y)
    }

    /**
     * A thread of movement under the panel while there is still nothing in it.
     *
     * Not diegetic, and a deliberate exception: a black rectangle says nothing about whether it is coming.
     * It stays an exception by being scarce — nothing at all for the first [BEFORE_SAYING_SO_NANOS], which
     * an Age whose footing is near its origin never exceeds, and gone the instant a chunk is drawn.
     *
     * It travels rather than fills because the client has nothing to fill it with: until the server sends
     * the level payload it does not know the Age exists, let alone how far along it is.
     */
    private fun drawWaiting(graphics: GuiGraphicsExtractor, x: Int, y: Int) {
        if (!LinkingPanel.isWaiting) return
        val waited = System.nanoTime() - openedAt
        if (waited < BEFORE_SAYING_SO_NANOS) return

        // Along the panel's own bottom border, which is the track: a picture with something moving in its
        // frame rather than a picture with a bar under it.
        val along = y + PanelPicture.HEIGHT
        val throughSweep = ((waited % SWEEP_NANOS).toDouble() / SWEEP_NANOS).toFloat()
        // Back and forth, so the mark never jumps from one end of the border to the other.
        val alongTheTrack = if (throughSweep < 0.5f) throughSweep * 2 else (1.0f - throughSweep) * 2
        val from = x + ((PanelPicture.WIDTH - WAITING_MARK) * alongTheTrack).toInt()
        graphics.fill(from, along, from + WAITING_MARK, along + WAITING_HEIGHT, WAITING_INK)
    }

    /** One page of writing, or nothing where the book has no such page. */
    private fun drawPage(graphics: GuiGraphicsExtractor, x: Int, top: Int, at: Int) {
        val page = pages.getOrNull(at) ?: return
        var y = top + writingBeginsOn(at)
        for (line in page) {
            var column = x
            for (word in line.words) {
                scaled(graphics, column, y, SCRIPT_SCALE) {
                    graphics.text(font, word.script, 0, 0, ParchmentSurface.INK, false)
                }
                scaled(graphics, column, y + scriptHeight(), READING_SCALE) {
                    graphics.text(font, word.reading, 0, 0, ParchmentSurface.FAINT_INK, false)
                }
                column += word.width
            }
            y += lineHeight()
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
     * The book's prose packed into lines, and the lines into pages.
     *
     * **Each word is set over its own transliteration**, the D'ni as ornament on an English account, so a
     * line is a run of columns rather than a run of text and a page break can only fall between columns.
     */
    private fun paginate(): List<List<Line>> {
        val cut = mutableListOf<List<Line>>()
        var page = mutableListOf<Line>()
        for (line in linesOfWriting()) {
            if (page.isNotEmpty() && (page.size + 1) * lineHeight() > roomOn(cut.size)) {
                cut += page
                page = mutableListOf()
            }
            page += line
        }
        if (page.isNotEmpty()) cut += page
        return cut
    }

    /** The columns packed left to right into lines that fit the writing column. */
    private fun linesOfWriting(): List<Line> {
        val lines = mutableListOf<Line>()
        var line = mutableListOf<Column>()
        var used = 0
        for (word in wordsOfWriting().map(::columnOf)) {
            if (line.isNotEmpty() && used + word.width > COLUMN_WIDTH) {
                lines += Line(line)
                line = mutableListOf()
                used = 0
            }
            line += word
            used += word.width
        }
        if (line.isNotEmpty()) lines += Line(line)
        return lines
    }

    /** The prose, written in this client's language and broken into its words. */
    private fun wordsOfWriting(): List<String> {
        val clauses = book.get(AgeComponents.BOOK_READING).orEmpty()
        return ProseWriting.sentencesOf(clauses)
            .flatMap { it.split(' ') }
            .filter { it.isNotBlank() }
    }

    /** One word, under the script that spells it. */
    private fun columnOf(word: String): Column = Column(KnownWords.scriptLine(word), Component.literal(word))

    private fun scriptHeight(): Int = (font.lineHeight * SCRIPT_SCALE).toInt() + 1

    private fun lineHeight(): Int = scriptHeight() + (font.lineHeight * READING_SCALE).toInt() + LINE_GAP

    /** Where writing starts down a page — the first one begins under the title, the rest at the top. */
    private fun writingBeginsOn(page: Int): Int = if (page == 0) TEXT_Y else TOP_MARGIN

    private fun roomOn(page: Int): Int = HEIGHT - writingBeginsOn(page) - BOTTOM_MARGIN

    /** Which page of writing sits where, given a spread. The first spread's left leaf is the panel. */
    private fun leftPageOf(spread: Int): Int = 2 * spread - 1

    private fun rightPageOf(spread: Int): Int = 2 * spread

    private fun canTurnForward(): Boolean = leftPageOf(spread + 1) <= pages.lastIndex

    /** A run of columns that fits the writing column, set as script over reading. */
    private class Line(val words: List<Column>)

    /**
     * One column: a word as the script sets it, over what it says.
     *
     * [width] is the wider of the two at their own scales, so the pair occupies a column of its own and the
     * next starts clear of it — which is what keeps a reading under the word it belongs to rather than
     * under whatever happens to be above it.
     */
    private inner class Column(val script: Component, val reading: Component) {
        val width: Int = maxOf(
            (font.width(script) * SCRIPT_SCALE).toInt(),
            (font.width(reading) * READING_SCALE).toInt(),
        ) + COLUMN_GAP
    }

    /** Draws [body] at [scale] with the origin moved to ([x], [y]), since text is placed by its corner. */
    private fun scaled(graphics: GuiGraphicsExtractor, x: Int, y: Int, scale: Float, body: () -> Unit) {
        graphics.pose().pushMatrix()
        graphics.pose().translate(x.toFloat(), y.toFloat())
        graphics.pose().scale(scale, scale)
        body()
        graphics.pose().popMatrix()
    }

    /** Anywhere on the open book, spine and edges included — so what is *not* this is the world behind it. */
    private fun overTheBook(mouseX: Double, mouseY: Double): Boolean {
        val left = (width - WIDTH) / 2
        val top = (height - HEIGHT) / 2
        return mouseX >= left && mouseX <= left + WIDTH && mouseY >= top && mouseY <= top + HEIGHT
    }

    /** How far across the book the panel begins: [PANEL_X] into whichever leaf it is on. */
    private fun panelAcrossTheBook(): Int = PANEL_X + if (panelPage == BookPage.RIGHT) WIDTH / 2 else 0

    private fun overPanel(mouseX: Double, mouseY: Double): Boolean {
        val x = (width - WIDTH) / 2 + panelAcrossTheBook()
        val y = (height - HEIGHT) / 2 + PANEL_Y
        return mouseX >= x && mouseX <= x + PanelPicture.WIDTH && mouseY >= y && mouseY <= y + PanelPicture.HEIGHT
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
        // **Right-click shuts the book, wherever it lands.** The book was opened with a right-click and
        // closing it the same way is what a hand does; escape alone left the only way out on the keyboard.
        if (event.button() == RIGHT_BUTTON) {
            onClose()
            return true
        }
        // **And so does clicking off the book**, which is what a screen with a small object in the middle
        // of it reads as. Everything inside is a page, the panel or the spine, and each is handled below.
        if (!overTheBook(event.x, event.y)) {
            onClose()
            return true
        }
        val clickedThePanel = spread == 0 && overPanel(event.x, event.y)
        if (clickedThePanel && held is BookBeingRead.InHand) {
            sendToServer(LinkRequest(held.hand))
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
        /** GLFW's right button, which is what a book is opened with and now what closes it. */
        const val RIGHT_BUTTON = 1

        const val WIDTH = 256
        const val HEIGHT = 180

        /** Centred on the leaf, which the writing columns are not: they carry a book's wider outer margin. */
        const val PANEL_X = (WIDTH / 2 - PanelPicture.WIDTH) / 2
        const val PANEL_Y = 30

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

        /** Between one glossed line and the next, so a pair reads as a pair. */
        const val LINE_GAP = 5

        /** Clear space after a column, so two of them do not read as one word. */
        const val COLUMN_GAP = 4

        val PANEL_LIT = 0x18FFFFFF

        /** How long a panel may be empty before it admits to it. */
        const val BEFORE_SAYING_SO_NANOS = 2_000_000_000L

        /** One pass of the mark along the track. */
        const val SWEEP_NANOS = 1_600_000_000L

        const val WAITING_HEIGHT = 2
        const val WAITING_MARK = 22

        /** Read against [ParchmentSurface.EDGE], which it travels along. */
        val WAITING_INK = 0xAA2B2118.toInt()
    }
}

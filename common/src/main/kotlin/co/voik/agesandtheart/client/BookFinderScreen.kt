package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.BookFinderFetchPayload
import co.voik.agesandtheart.book.BookFinderListPayload
import co.voik.agesandtheart.book.BookFinders
import co.voik.agesandtheart.book.FinderBook
import co.voik.agesandtheart.client.ui.DecorationWidget
import co.voik.agesandtheart.client.ui.FlexColumn
import co.voik.agesandtheart.client.ui.LabelledList
import co.voik.agesandtheart.client.ui.Palette
import co.voik.agesandtheart.client.ui.PanelSurface
import co.voik.agesandtheart.client.ui.Rect
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component

/** Opens a finder's list, kept apart so the block never names a client class directly. */
object BookFinderOpener {
    fun list(payload: BookFinderListPayload) {
        Minecraft.getInstance().setScreenAndShow(BookFinderScreen(payload))
    }
}

/**
 * A finder's wall of books, searched by name, as the archive's pages are, each with its inventory tooltip. Clicking one
 * sends the frame for it; while
 * the frame is busy the choice waits its turn, which the server says.
 */
class BookFinderScreen(private val payload: BookFinderListPayload) : Screen(BookFinders.BLOCK.name) {

    private lateinit var search: EditBox
    private lateinit var list: LabelledList<FinderBook>
    private var searchWaited: Int? = null

    override fun init() {
        val keptFilter = if (::search.isInitialized) search.value else ""
        val panelWidth = minOf(PANEL_WIDTH, width - MARGIN * 2)
        val panelHeight = minOf(PANEL_HEIGHT, height - MARGIN * 2)
        val left = (width - panelWidth) / 2
        val top = (height - panelHeight) / 2
        addRenderableWidget(
            DecorationWidget(PanelSurface.RAISED).also {
                it.setPosition(left, top)
                it.setSize(panelWidth, panelHeight)
            },
        )
        search = EditBox(font, 0, 0, 0, LINE, Component.empty())
        search.setHint(translated("search"))
        search.value = keptFilter
        search.setResponder { searchWaited = 0 }
        addRenderableWidget(search)
        list = addRenderableWidget(LabelledList(Minecraft.getInstance(), Rect(0, 0, 0, 0), ::fetch))
        val column = FlexColumn(panelWidth - INSET * 2, panelHeight - CONTENT_TOP - INSET)
        column.add(search, height = LINE)
        column.gap(GAP)
        column.fill(list)
        column.setPosition(left + INSET, top + CONTENT_TOP)
        column.arrangeElements()
        titleTop = top + TITLE_TOP
        titleLeft = left + INSET
        focused = search
        refresh()
    }

    private var titleTop = 0
    private var titleLeft = 0

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, a)
        graphics.text(font, title, titleLeft, titleTop, Palette.TEXT, false)
        if (payload.books.isEmpty()) {
            graphics.textWithWordWrap(font, translated("empty"), list.x + HINT_INSET, list.y + HINT_INSET, list.width - 2 * HINT_INSET, Palette.FAINT)
        }
    }

    override fun tick() {
        super.tick()
        val waited = searchWaited ?: return
        if (waited < SEARCH_PAUSE_TICKS) {
            searchWaited = waited + 1
            return
        }
        searchWaited = null
        list.resetScroll()
        refresh()
    }

    /** A key belongs to the search box while it has the caret, or `e` would close the screen mid-word. */
    override fun keyPressed(event: KeyEvent): Boolean {
        if (!search.isFocused) return super.keyPressed(event)
        if (search.keyPressed(event)) return true
        return if (event.isEscape) super.keyPressed(event) else true
    }

    override fun isPauseScreen(): Boolean = false

    private fun refresh() {
        val needle = search.value.trim().lowercase()
        val shown = if (needle.isEmpty()) payload.books else payload.books.filter { needle in it.name.lowercase() }
        list.show(shown, label = { it.name }, key = { it.shelf to it.slot }, tooltip = ::tooltipOf)
    }

    /** A click on a book sends the frame for it (Jonah, 2026-11-19). */
    private fun fetch(book: FinderBook) {
        sendToServer(BookFinderFetchPayload(payload.finder, book.shelf, book.slot))
        onClose()
    }

    /** What the book would say of itself in an inventory. */
    private fun tooltipOf(book: FinderBook): List<Component> = getTooltipFromItem(Minecraft.getInstance(), book.book)

    private fun translated(suffix: String): Component = Component.translatable("container.agesandtheart.book_finder.$suffix")

    private companion object {
        const val PANEL_WIDTH = 220
        const val PANEL_HEIGHT = 200
        const val MARGIN = 8
        const val INSET = 8
        const val LINE = 12
        const val GAP = 4
        const val TITLE_TOP = 7
        const val CONTENT_TOP = 20
        const val HINT_INSET = 2
        const val SEARCH_PAUSE_TICKS = 4
    }
}

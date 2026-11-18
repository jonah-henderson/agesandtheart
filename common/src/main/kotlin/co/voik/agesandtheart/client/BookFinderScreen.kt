package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.BookBeingRead
import co.voik.agesandtheart.book.BookFinderAskPayload
import co.voik.agesandtheart.book.BookFinderFetchPayload
import co.voik.agesandtheart.book.BookFinderListPayload
import co.voik.agesandtheart.book.BookFinders
import co.voik.agesandtheart.book.FinderAsked
import co.voik.agesandtheart.book.FinderBook
import co.voik.agesandtheart.client.ui.DecorationWidget
import co.voik.agesandtheart.client.ui.FlexColumn
import co.voik.agesandtheart.client.ui.LabelledList
import co.voik.agesandtheart.client.ui.Palette
import co.voik.agesandtheart.client.ui.PanelSurface
import co.voik.agesandtheart.client.ui.Rect
import co.voik.agesandtheart.client.ui.RowAction
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.BookViewScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

/** Opens a finder's screens, kept apart so the block never names a client class directly. */
object BookFinderOpener {

    /** A book of ours the frame holds open, read as one on a lectern is. */
    fun readOurs(book: ItemStack, finder: BlockPos) {
        Minecraft.getInstance().setScreenAndShow(BookScreen(book, BookBeingRead.InAFinder(finder)))
    }

    /** A written book the frame holds open, in vanilla's pages, with the finder's buttons under them. */
    fun readWritten(book: ItemStack, finder: BlockPos) {
        Minecraft.getInstance().setScreenAndShow(FinderBookViewScreen(book, finder))
    }

    fun list(payload: BookFinderListPayload) {
        Minecraft.getInstance().setScreenAndShow(BookFinderScreen(payload))
    }
}

/** Vanilla's written book, read from a finder's frame: **Done**, **Take Book** and **Send Back** under it, as a lectern's has. */
class FinderBookViewScreen(book: ItemStack, private val finder: BlockPos) : BookViewScreen(BookAccess.fromItem(book) ?: BookAccess(emptyList())) {

    override fun createMenuControls() {
        val buttonWidth = (IMAGE_WIDTH_OF_THE_ROW - GAP * 2) / 3
        val left = (width - IMAGE_WIDTH_OF_THE_ROW) / 2
        val top = menuControlsTop()
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE) { onClose() }.bounds(left, top, buttonWidth, BUTTON_HEIGHT).build())
        addRenderableWidget(
            Button.builder(Component.translatable(TAKE_BOOK)) { asked(FinderAsked.TAKE) }
                .bounds(left + buttonWidth + GAP, top, buttonWidth, BUTTON_HEIGHT).build(),
        )
        addRenderableWidget(
            Button.builder(Component.translatable(SEND_BACK)) { asked(FinderAsked.SEND_BACK) }
                .bounds(left + (buttonWidth + GAP) * 2, top, buttonWidth, BUTTON_HEIGHT).build(),
        )
    }

    private fun asked(asked: FinderAsked) {
        sendToServer(BookFinderAskPayload(finder, asked))
        onClose()
    }

    private companion object {
        const val TAKE_BOOK = "lectern.take_book"
        const val SEND_BACK = "book.agesandtheart.send_back"
        const val IMAGE_WIDTH_OF_THE_ROW = 3 * 80 + 2 * 4
        const val GAP = 4
        const val BUTTON_HEIGHT = 20
    }
}

/**
 * A finder's wall of books, searched by name, as the archive's pages are. Choosing one sends the frame for it; while
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
        list = addRenderableWidget(LabelledList(Minecraft.getInstance(), Rect(0, 0, 0, 0)) {})
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
        list.show(shown, label = { it.name }, key = { it.shelf to it.slot }, actions = listOf(fetch))
    }

    private val fetch = RowAction<FinderBook>(
        glyph = "⇲",
        tooltip = { translated("fetch") },
        act = { book ->
            sendToServer(BookFinderFetchPayload(payload.finder, book.shelf, book.slot))
            onClose()
        },
    )

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

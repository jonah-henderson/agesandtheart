package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.desk.DeskAction
import co.voik.agesandtheart.desk.DeskCommandPayload
import co.voik.agesandtheart.desk.WritersDeskMenu
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack

/** The three things a desk is for. */
enum class DeskTab(val title: String) {
    ARCHIVE("archive"),
    WRITE_PAGE("write_page"),
    WRITE_BOOK("write_book"),
}

/**
 * The desk.
 *
 * Vanilla widgets throughout (see `notes/ui-libraries-research.md`), laid out as tabs across the top in
 * JEI's shape. The slots and the player inventory are the container screen's; everything else is drawn
 * from [DeskModel], which the server keeps current.
 */
class WritersDeskScreen(
    menu: WritersDeskMenu,
    inventory: Inventory,
    title: Component,
) : AbstractContainerScreen<WritersDeskMenu>(menu, inventory, title, WIDTH, HEIGHT) {

    private var tab = DeskTab.ARCHIVE
    private var filter = ""
    private var scroll = 0
    private var search: EditBox? = null
    private var name: EditBox? = null

    override fun init() {
        super.init()
        rebuild()
    }

    private fun rebuild() {
        clearWidgets()
        addTabs()
        addSearch()
        // Both writing tabs get them: the decision is identical, so the control is (design §7.4).
        if (tab != DeskTab.ARCHIVE) addPaperButtons()
        if (tab == DeskTab.WRITE_BOOK) addBookControls()
    }

    private fun addTabs() {
        DeskTab.entries.forEachIndexed { index, entry ->
            val button = Button.builder(Component.translatable(tabKey(entry))) {
                tab = entry
                scroll = 0
                rebuild()
            }.bounds(leftPos + PANE_X + index * TAB_WIDTH, topPos + TAB_Y, TAB_WIDTH - 2, TAB_HEIGHT).build()
            button.active = entry != tab
            addRenderableWidget(button)
        }
    }

    private fun addSearch() {
        val box = EditBox(font, leftPos + PANE_X, topPos + SEARCH_Y, PANE_WIDTH, LINE, Component.empty())
        box.setHint(Component.translatable("container.agesandtheart.writers_desk.search"))
        box.value = filter
        box.setResponder { typed ->
            filter = typed
            scroll = 0
        }
        search = box
        addRenderableWidget(box)
    }

    /**
     * The three paper choices, as icons.
     *
     * Selecting a word does not write it — these do (design §7.4). Three buttons because better paper
     * spends less ink, so they are three prices for the same word and the choice is which resource to
     * spend. One button would have to pick for the player, and the whole axis would vanish.
     */
    private fun addPaperButtons() {
        InkTier.entries.forEachIndexed { index, paper ->
            val button = Button.builder(Component.literal(paperGlyph(paper))) {
                val into = if (tab == DeskTab.WRITE_BOOK) DeskAction.WRITE_TO_BOOK else DeskAction.WRITE_TO_ARCHIVE
                selected?.let { word -> send(into, word, paper) }
            }.bounds(
                leftPos + PANE_X + index * (PAPER_BUTTON + 2),
                topPos + PAPER_BUTTON_Y,
                PAPER_BUTTON,
                LINE,
            ).build()
            button.active = selected != null && DeskModel.paper(paper) > 0 && affordable(paper)
            addRenderableWidget(button)
        }
    }

    /** Whether the tanks actually hold what this paper would cost. */
    private fun affordable(paper: InkTier): Boolean {
        val (inkTier, units) = DeskModel.priceFor(selected, paper) ?: return true
        return DeskModel.ink(inkTier) >= units
    }

    /**
     * The price under each button.
     *
     * Without this the three buttons are three identical squares and the efficiency axis is invisible —
     * the whole reason there are three is that they cost differently (design §7.4).
     */
    private fun drawPrices(graphics: GuiGraphicsExtractor) {
        val word = selected ?: return
        InkTier.entries.forEachIndexed { index, paper ->
            val price = DeskModel.priceFor(word, paper) ?: return@forEachIndexed
            val x = leftPos + PANE_X + index * (PAPER_BUTTON + 2)
            val y = topPos + PAPER_BUTTON_Y + LINE + 1
            val colour = if (DeskModel.ink(price.first).toDouble() >= price.second) INK else SHORT
            graphics.text(font, inkGlyph(price.first) + drops(price.second), x, y, colour)
        }
    }

    /** Ink shown in tenths of a bucket, which is the scale a page actually costs. */
    private fun drops(units: Long): String {
        val tenths = (units * 10.0 / DeskModel.inkCapacity().coerceAtLeast(1) * TANK_BUCKETS).toInt()
        return if (tenths <= 0) "<1" else "$tenths"
    }

    private fun inkGlyph(tier: InkTier): String = when (tier) {
        InkTier.COMMON -> "i"
        InkTier.FINE -> "ii"
        InkTier.MASTERWORK -> "iii"
    }

    private fun addBookControls() {
        val box = EditBox(font, leftPos + PANE_X, topPos + NAME_Y, PANE_WIDTH - BIND_WIDTH - 4, LINE, Component.empty())
        box.setHint(Component.translatable("container.agesandtheart.writers_desk.name"))
        box.setMaxLength(DeskCommandPayload.MAX_TITLE)
        name = box
        addRenderableWidget(box)

        addRenderableWidget(
            Button.builder(Component.translatable("container.agesandtheart.writers_desk.bind")) {
                send(DeskAction.FINALISE, null, InkTier.COMMON, title = name?.value.orEmpty())
            }.bounds(
                leftPos + PANE_X + PANE_WIDTH - BIND_WIDTH,
                topPos + NAME_Y,
                BIND_WIDTH,
                LINE,
            ).build(),
        )
    }

    private var selected: Identifier? = null

    private fun rows(): List<WordRow> = when (tab) {
        DeskTab.ARCHIVE -> DeskModel.archiveRows(filter)
        DeskTab.WRITE_PAGE -> DeskModel.writableRows(filter)
        DeskTab.WRITE_BOOK -> DeskModel.writableRows(filter)
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractBackground(graphics, mouseX, mouseY, a)
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, EDGE)
        graphics.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + imageHeight - 1, PARCHMENT)
        drawStocks(graphics)
        drawRows(graphics)
        if (tab != DeskTab.ARCHIVE) drawPrices(graphics)
        if (tab == DeskTab.WRITE_BOOK) drawComposition(graphics)
    }

    /**
     * The stock panel, always visible whichever tab is up.
     *
     * Ink is three vertical bars in the brewing stand's shape, each tinted to its own ink; paper is three
     * counts beneath. Both belong to every tab, because every tab spends them.
     */
    private fun drawStocks(graphics: GuiGraphicsExtractor) {
        InkTier.entries.forEachIndexed { index, tier ->
            val x = leftPos + STOCK_X + index * (BAR_WIDTH + 4)
            val top = topPos + BAR_Y
            graphics.fill(x - 1, top - 1, x + BAR_WIDTH + 1, top + BAR_HEIGHT + 1, EDGE)
            graphics.fill(x, top, x + BAR_WIDTH, top + BAR_HEIGHT, EMPTY_TANK)
            val filled = (BAR_HEIGHT * DeskModel.ink(tier).toDouble() / DeskModel.inkCapacity()).toInt()
            if (filled > 0) {
                graphics.fill(x, top + BAR_HEIGHT - filled, x + BAR_WIDTH, top + BAR_HEIGHT, tint(tier))
            }
        }
        InkTier.entries.forEachIndexed { index, tier ->
            val y = topPos + PAPER_Y + index * (LINE + 2)
            graphics.item(paperIcon(tier), leftPos + STOCK_X, y)
            graphics.text(font, "${DeskModel.paper(tier)}", leftPos + STOCK_X + 20, y + 4, INK)
        }
        drawInputs(graphics)
    }

    /**
     * The two things you hand the desk, drawn where the menu put them.
     *
     * Both live in the stock panel rather than in a tab, so they are reachable whichever tab is up —
     * dropping leather in while browsing the archive should not require finding the right screen first.
     */
    private fun drawInputs(graphics: GuiGraphicsExtractor) {
        drawSlot(graphics, INTAKE_SLOT_X, SLOT_Y, "in")
        drawSlot(graphics, BINDING_SLOT_X, SLOT_Y, "bind")
    }

    private fun drawSlot(graphics: GuiGraphicsExtractor, x: Int, y: Int, labelKey: String) {
        val left = leftPos + x
        val top = topPos + y
        graphics.fill(left - 1, top - 1, left + SLOT + 1, top + SLOT + 1, EDGE)
        graphics.fill(left, top, left + SLOT, top + SLOT, EMPTY_TANK)
        graphics.text(
            font,
            Component.translatable("container.agesandtheart.writers_desk.$labelKey"),
            left,
            top - LINE + 2,
            FAINT_INK,
        )
    }

    private fun drawRows(graphics: GuiGraphicsExtractor) {
        val visible = rows().drop(scroll).take(VISIBLE_ROWS)
        visible.forEachIndexed { index, row ->
            val y = topPos + ROWS_Y + index * LINE
            if (row.word == selected) {
                graphics.fill(leftPos + PANE_X, y - 1, leftPos + PANE_X + PANE_WIDTH, y + LINE - 2, HIGHLIGHT)
            }
            graphics.text(font, row.readable, leftPos + PANE_X + 2, y, INK)
            if (row.inArchive > 0) {
                graphics.text(font, "${row.inArchive}", leftPos + PANE_X + PANE_WIDTH - 20, y, FAINT_INK)
            }
        }
    }

    /** The sentence so far. Order is word order, so it reads left to right like the thing it becomes. */
    private fun drawComposition(graphics: GuiGraphicsExtractor) {
        val words = DeskModel.composing()
        val limit = DeskModel.pageLimit()
        val header = if (limit == null) "${words.size}" else "${words.size} / $limit"
        graphics.text(font, header, leftPos + PANE_X, topPos + COMPOSE_Y - LINE, FAINT_INK)
        words.forEachIndexed { index, word ->
            val x = leftPos + PANE_X + (index % COMPOSE_COLUMNS) * COMPOSE_CELL
            val y = topPos + COMPOSE_Y + (index / COMPOSE_COLUMNS) * LINE
            graphics.text(font, co.voik.agesandtheart.age.word.WordNames.readable(word), x, y, INK)
        }
    }

    override fun mouseClicked(event: net.minecraft.client.input.MouseButtonEvent, doubleClick: Boolean): Boolean {
        val handled = clickComposed(event.x, event.y) || clickRow(event.x, event.y)
        return handled || super.mouseClicked(event, doubleClick)
    }

    /** Which laid-out page the mouse took hold of, and whether it has actually travelled. */
    private var dragFrom: Int? = null
    private var dragMoved = false

    /** The cell under the cursor, or null. Shared by the press, the drag and the release. */
    private fun composedCellAt(mouseX: Double, mouseY: Double): Int? {
        DeskModel.composing().indices.forEach { index ->
            val x = leftPos + PANE_X + (index % COMPOSE_COLUMNS) * COMPOSE_CELL
            val y = topPos + COMPOSE_Y + (index / COMPOSE_COLUMNS) * LINE
            if (mouseX >= x && mouseX <= x + COMPOSE_CELL && mouseY >= y && mouseY <= y + LINE) return index
        }
        return null
    }

    override fun mouseDragged(
        event: net.minecraft.client.input.MouseButtonEvent,
        dragX: Double,
        dragY: Double,
    ): Boolean {
        if (dragFrom != null) {
            // A press that never really travels is a click, and a click removes rather than reorders.
            if (kotlin.math.abs(dragX) + kotlin.math.abs(dragY) > DRAG_SLOP) dragMoved = true
            return true
        }
        return super.mouseDragged(event, dragX, dragY)
    }

    /**
     * Letting go settles what the gesture meant.
     *
     * Dropped somewhere else, it is a reorder; dropped where it started — or barely moved at all — it is
     * the click that takes the page back. One gesture, two meanings, told apart by whether it travelled.
     */
    override fun mouseReleased(event: net.minecraft.client.input.MouseButtonEvent): Boolean {
        val from = dragFrom
        if (from != null) {
            dragFrom = null
            val onto = composedCellAt(event.x, event.y)
            if (dragMoved && onto != null && onto != from) {
                ClientDeskNetwork.send(
                    DeskCommandPayload(DeskAction.MOVE_IN_BOOK, null, InkTier.COMMON, from, onto, ""),
                )
            } else if (!dragMoved) {
                ClientDeskNetwork.send(
                    DeskCommandPayload(DeskAction.RETURN_TO_ARCHIVE, null, InkTier.COMMON, from, -1, ""),
                )
            }
            dragMoved = false
            return true
        }
        return super.mouseReleased(event)
    }

    /** Clicking a laid-out page takes it back; dragging one moves it. */
    private fun clickComposed(mouseX: Double, mouseY: Double): Boolean {
        if (tab != DeskTab.WRITE_BOOK) return false
        composedCellAt(mouseX, mouseY)?.let { index ->
            run {
                dragFrom = index
                dragMoved = false
                return true
            }
        }
        return false
    }

    private fun clickRow(mouseX: Double, mouseY: Double): Boolean {
        val visible = rows().drop(scroll).take(VISIBLE_ROWS)
        visible.forEachIndexed { index, row ->
            val y = topPos + ROWS_Y + index * LINE
            val inside = mouseX >= leftPos + PANE_X && mouseX <= leftPos + PANE_X + PANE_WIDTH &&
                mouseY >= y - 1 && mouseY <= y + LINE - 2
            if (!inside) return@forEachIndexed
            if (selected != row.word) {
                selected = row.word
                send(DeskAction.PRICE, row.word, InkTier.COMMON)
            }
            when (tab) {
                // A page you own goes straight in; one you do not is written by the paper buttons.
                DeskTab.WRITE_BOOK ->
                    if (row.inArchive > 0) send(DeskAction.COMPOSE_FROM_ARCHIVE, row.word, InkTier.COMMON)
                DeskTab.ARCHIVE -> send(DeskAction.WITHDRAW, row.word, InkTier.COMMON)
                DeskTab.WRITE_PAGE -> rebuild()
            }
            return true
        }
        return false
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, deltaX: Double, deltaY: Double): Boolean {
        val most = (rows().size - VISIBLE_ROWS).coerceAtLeast(0)
        scroll = (scroll - deltaY.toInt()).coerceIn(0, most)
        return true
    }

    private fun send(action: DeskAction, word: Identifier?, paper: InkTier, title: String = "") {
        ClientDeskNetwork.send(DeskCommandPayload(action, word, paper, index = -1, target = -1, title = title))
    }

    private fun tint(tier: InkTier): Int = AgeContent.let { _ ->
        co.voik.agesandtheart.content.AgeFluids.INKS[tier]?.tint ?: INK
    }

    private fun paperIcon(tier: InkTier): ItemStack = when (tier) {
        InkTier.COMMON -> ItemStack(net.minecraft.world.item.Items.PAPER)
        InkTier.FINE -> ItemStack(AgeContent.FINE_PAPER)
        InkTier.MASTERWORK -> ItemStack(AgeContent.MASTERWORK_PAPER)
    }

    private fun paperGlyph(tier: InkTier): String = when (tier) {
        InkTier.COMMON -> "I"
        InkTier.FINE -> "II"
        InkTier.MASTERWORK -> "III"
    }

    private fun tabKey(entry: DeskTab) = "container.agesandtheart.writers_desk.${entry.title}"

    companion object {
        const val WIDTH = 256
        const val HEIGHT = 256

        const val TAB_Y = 6
        const val TAB_WIDTH = 56
        const val TAB_HEIGHT = 16

        const val STOCK_X = 8
        const val BAR_Y = 30
        const val BAR_WIDTH = 10
        const val BAR_HEIGHT = 48
        const val PAPER_Y = 84

        /** Must match the menu's slot positions, which is where the items are actually drawn. */
        const val SLOT = 16
        const val INTAKE_SLOT_X = 8
        const val BINDING_SLOT_X = 30
        const val SLOT_Y = 140

        const val PANE_X = 60
        const val PANE_WIDTH = 188
        const val SEARCH_Y = 26
        const val ROWS_Y = 44
        const val LINE = 12
        const val VISIBLE_ROWS = 6

        const val PAPER_BUTTON = 30
        const val PAPER_BUTTON_Y = 120

        const val COMPOSE_Y = 122
        const val COMPOSE_COLUMNS = 4
        const val COMPOSE_CELL = 46

        /** Pixels a press may wander before it counts as a drag rather than a click. */
        const val DRAG_SLOP = 3.0

        const val NAME_Y = 156
        const val BIND_WIDTH = 44

        val PARCHMENT = 0xFFE9DFC3.toInt()
        val EDGE = 0xFF8B7B55.toInt()
        val INK = 0xFF2B2118.toInt()
        val FAINT_INK = 0xFF6B5C46.toInt()
        val EMPTY_TANK = 0xFFC9BC9A.toInt()
        val HIGHLIGHT = 0x40000000
        val SHORT = 0xFF8B2E2E.toInt()

        /** Matches AgeFluids.TANK_CAPACITY_BUCKETS; only used to turn synced units back into buckets. */
        const val TANK_BUCKETS = 64
    }
}

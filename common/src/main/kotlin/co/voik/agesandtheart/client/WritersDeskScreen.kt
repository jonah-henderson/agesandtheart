package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.age.word.WordNames
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.AgeFluids
import co.voik.agesandtheart.desk.DeskAction
import co.voik.agesandtheart.desk.DeskCommandPayload
import co.voik.agesandtheart.desk.WritersDeskMenu
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/** The three things a desk is for. */
enum class DeskTab(val title: String, val icon: () -> ItemStack) {
    ARCHIVE("archive", { ItemStack(AgeContent.PAGE) }),
    WRITE_PAGE("write_page", { ItemStack(Items.PAPER) }),
    WRITE_BOOK("write_book", { ItemStack(AgeContent.DESCRIPTIVE_BOOK) }),
}

/**
 * The desk, dressed as vanilla.
 *
 * Large-chest proportions exactly — 176×222, the player's half pixel-identical to a double chest — so the
 * half of the screen a player already knows behaves like the thing they know. Where a chest puts six rows
 * of slots, this puts whichever tab is open.
 *
 * The tabs are the creative inventory's, sprite for sprite and at its geometry. The stock panel hangs off
 * the left edge, drawn with vanilla's own nine-patch: a pixel of black, two of white on the top and left,
 * two of grey on the bottom and right, `C6C6C6` between — all sampled from `generic_54.png` rather than
 * guessed at.
 */
class WritersDeskScreen(
    menu: WritersDeskMenu,
    inventory: Inventory,
    title: Component,
) : AbstractContainerScreen<WritersDeskMenu>(menu, inventory, title, WIDTH, HEIGHT) {

    private var tab = DeskTab.ARCHIVE
    private var filter = ""
    private var scroll = 0
    private var selected: Identifier? = null
    private var name: EditBox? = null
    private var dragFrom: Int? = null
    private var dragMoved = false

    override fun init() {
        super.init()
        rebuild()
    }

    private fun rebuild() {
        clearWidgets()
        addSearch()
        if (tab != DeskTab.ARCHIVE) addPaperButtons()
        if (tab == DeskTab.WRITE_BOOK) addBookControls()
    }

    private fun addSearch() {
        val box = EditBox(font, leftPos + PANEL_X, topPos + SEARCH_Y, PANEL_WIDTH, LINE, Component.empty())
        box.setHint(Component.translatable("container.agesandtheart.writers_desk.search"))
        box.value = filter
        box.setResponder { typed ->
            filter = typed
            scroll = 0
        }
        addRenderableWidget(box)
    }

    /** Three prices for the same word: which resource you would rather spend (design §7.4). */
    private fun addPaperButtons() {
        InkTier.entries.forEachIndexed { index, paper ->
            val button = Button.builder(Component.literal(paperGlyph(paper))) {
                val into = if (tab == DeskTab.WRITE_BOOK) DeskAction.WRITE_TO_BOOK else DeskAction.WRITE_TO_ARCHIVE
                selected?.let { word -> send(into, word, paper) }
            }.bounds(
                leftPos + PANEL_X + index * (PAPER_BUTTON + 2),
                topPos + BUTTON_Y,
                PAPER_BUTTON,
                LINE + 2,
            ).build()
            button.active = selected != null && DeskModel.paper(paper) > 0 && affordable(paper)
            addRenderableWidget(button)
        }
    }

    private fun addBookControls() {
        val box = EditBox(font, leftPos + COMPOSE_X, topPos + NAME_Y, NAME_WIDTH, LINE, Component.empty())
        box.setHint(Component.translatable("container.agesandtheart.writers_desk.name"))
        box.setMaxLength(DeskCommandPayload.MAX_TITLE)
        name = box
        addRenderableWidget(box)
        addRenderableWidget(
            Button.builder(Component.translatable("container.agesandtheart.writers_desk.bind")) {
                send(DeskAction.FINALISE, null, InkTier.COMMON, title = name?.value.orEmpty())
            }.bounds(leftPos + COMPOSE_X + NAME_WIDTH + 2, topPos + NAME_Y, BIND_WIDTH, LINE).build(),
        )
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractBackground(graphics, mouseX, mouseY, a)
        // The chest in its two halves, then the slot grid painted out — our top half is not slots.
        graphics.blit(RenderPipelines.GUI_TEXTURED, CHEST, leftPos, topPos, 0f, 0f, WIDTH, CHEST_TOP, 256, 256)
        graphics.blit(
            RenderPipelines.GUI_TEXTURED, CHEST,
            leftPos, topPos + CHEST_TOP, 0f, 126f, WIDTH, CHEST_BOTTOM, 256, 256,
        )
        graphics.fill(leftPos + 7, topPos + 17, leftPos + WIDTH - 7, topPos + CHEST_TOP, PANEL)

        extractTabs(graphics, mouseX, mouseY)
        extractStockWing(graphics, mouseX, mouseY)
        extractRows(graphics)
        if (tab != DeskTab.ARCHIVE) extractPrices(graphics)
        if (tab == DeskTab.WRITE_BOOK) extractComposition(graphics)
    }

    private fun extractTabs(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        DeskTab.entries.forEachIndexed { index, entry ->
            val x = leftPos + index * TAB_SPACING
            val y = topPos - TAB_LIFT
            val sprite = if (entry == tab) SELECTED_TABS[index] else UNSELECTED_TABS[index]
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, TAB_WIDTH, TAB_HEIGHT)
            graphics.item(entry.icon(), x + TAB_ICON_INSET, y + TAB_ICON_TOP)
            if (mouseX in x..(x + TAB_WIDTH) && mouseY in y..(y + TAB_HEIGHT)) {
                graphics.setTooltipForNextFrame(Component.translatable(tabKey(entry)), mouseX, mouseY)
            }
        }
    }

    /**
     * The stocks, on a panel hanging off the left edge.
     *
     * Outside the main panel because they belong to every tab — the tab decides what you are doing, the
     * wing says what you have to do it with, and that should not move when you switch.
     */
    private fun extractStockWing(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val x = leftPos - WING_WIDTH
        val y = topPos + WING_Y
        vanillaPanel(graphics, x, y, WING_WIDTH + WING_OVERLAP, WING_HEIGHT)

        InkTier.entries.forEachIndexed { index, tier ->
            val barX = x + WING_PAD + index * (BAR_WIDTH + BAR_GAP)
            val barY = y + WING_PAD
            graphics.fill(barX, barY, barX + BAR_WIDTH, barY + BAR_HEIGHT, SLOT)
            val held = DeskModel.ink(tier)
            val filled = (BAR_HEIGHT * held.toDouble() / DeskModel.inkCapacity().coerceAtLeast(1)).toInt()
            if (filled > 0) {
                graphics.fill(barX, barY + BAR_HEIGHT - filled, barX + BAR_WIDTH, barY + BAR_HEIGHT, tint(tier))
            }
            if (mouseX in barX..(barX + BAR_WIDTH) && mouseY in barY..(barY + BAR_HEIGHT)) {
                graphics.setTooltipForNextFrame(inkTooltip(tier, held), mouseX, mouseY)
            }
        }

        InkTier.entries.forEachIndexed { index, tier ->
            val rowY = y + PAPER_TOP + index * PAPER_LINE
            graphics.item(paperIcon(tier), x + WING_PAD, rowY)
            graphics.text(font, "${DeskModel.paper(tier)}", x + WING_PAD + PAPER_COUNT_X, rowY + 5, TEXT, false)
        }
    }

    /** Exactly what the tank holds, since a bar can only ever say roughly. */
    private fun inkTooltip(tier: InkTier, held: Long): Component {
        val perBucket = (DeskModel.inkCapacity() / AgeFluids.TANK_CAPACITY_BUCKETS).coerceAtLeast(1)
        return Component.translatable(
            "container.agesandtheart.writers_desk.ink",
            Component.translatable("container.agesandtheart.writers_desk.ink.${tier.key}"),
            String.format("%.2f", held.toDouble() / perBucket),
            AgeFluids.TANK_CAPACITY_BUCKETS,
        )
    }

    /** Vanilla's nine-patch, drawn rather than blitted: black, white highlight, grey shadow, C6 fill. */
    private fun vanillaPanel(graphics: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int) {
        graphics.fill(x, y, x + width, y + height, OUTLINE)
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, HIGHLIGHT)
        graphics.fill(x + 3, y + 3, x + width - 1, y + height - 1, SHADOW)
        graphics.fill(x + 3, y + 3, x + width - 3, y + height - 3, PANEL)
    }

    /** The word list is full width, except on the book tab where the sentence sits beside it. */
    private fun listWidth(): Int = if (tab == DeskTab.WRITE_BOOK) HALF_WIDTH else PANEL_WIDTH

    private fun rows(): List<WordRow> = when (tab) {
        DeskTab.ARCHIVE -> DeskModel.archiveRows(filter)
        else -> DeskModel.writableRows(filter)
    }

    private fun extractRows(graphics: GuiGraphicsExtractor) {
        rows().drop(scroll).take(VISIBLE_ROWS).forEachIndexed { index, row ->
            val y = topPos + ROWS_Y + index * LINE
            if (row.word == selected) {
                graphics.fill(leftPos + PANEL_X, y - 1, leftPos + PANEL_X + listWidth(), y + LINE - 2, SELECTION)
            }
            graphics.text(font, row.readable, leftPos + PANEL_X + 2, y, TEXT, false)
            if (row.inArchive > 0) {
                graphics.text(
                    font, "${row.inArchive}",
                    leftPos + PANEL_X + listWidth() - COUNT_INSET, y, FAINT, false,
                )
            }
        }
    }

    private fun extractPrices(graphics: GuiGraphicsExtractor) {
        val word = selected ?: return
        InkTier.entries.forEachIndexed { index, paper ->
            val price = DeskModel.priceFor(word, paper) ?: return@forEachIndexed
            val x = leftPos + PANEL_X + index * (PAPER_BUTTON + 2)
            val colour = if (DeskModel.ink(price.first) >= price.second) TEXT else SHORT
            graphics.text(font, inkGlyph(price.first), x, topPos + BUTTON_Y + LINE + 4, colour, false)
        }
    }

    private fun extractComposition(graphics: GuiGraphicsExtractor) {
        val words = DeskModel.composing()
        val limit = DeskModel.pageLimit()
        val header = if (limit == null) "${words.size}" else "${words.size} / $limit"
        graphics.text(font, header, leftPos + COMPOSE_X, topPos + COMPOSE_HEADER_Y, FAINT, false)
        words.take(VISIBLE_ROWS).forEachIndexed { index, word ->
            val y = topPos + ROWS_Y + index * LINE
            graphics.text(font, "${index + 1}. ", leftPos + COMPOSE_X, y, FAINT, false)
            graphics.text(font, WordNames.readable(word), leftPos + COMPOSE_X + ORDINAL_WIDTH, y, TEXT, false)
        }
    }

    private fun composedCellAt(mouseX: Double, mouseY: Double): Int? {
        if (tab != DeskTab.WRITE_BOOK) return null
        DeskModel.composing().indices.take(VISIBLE_ROWS).forEach { index ->
            val x = leftPos + COMPOSE_X
            val y = topPos + ROWS_Y + index * LINE
            if (mouseX >= x && mouseX <= x + HALF_WIDTH && mouseY >= y && mouseY <= y + LINE) return index
        }
        return null
    }

    override fun mouseClicked(event: net.minecraft.client.input.MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (clickTab(event.x, event.y)) return true
        composedCellAt(event.x, event.y)?.let {
            dragFrom = it
            dragMoved = false
            return true
        }
        if (clickRow(event.x, event.y)) return true
        return super.mouseClicked(event, doubleClick)
    }

    private fun clickTab(mouseX: Double, mouseY: Double): Boolean {
        DeskTab.entries.forEachIndexed { index, entry ->
            val x = leftPos + index * TAB_SPACING
            val y = topPos - TAB_LIFT
            if (mouseX >= x && mouseX <= x + TAB_WIDTH && mouseY >= y && mouseY <= y + TAB_HEIGHT) {
                if (entry != tab) {
                    tab = entry
                    scroll = 0
                    rebuild()
                }
                return true
            }
        }
        return false
    }

    private fun clickRow(mouseX: Double, mouseY: Double): Boolean {
        rows().drop(scroll).take(VISIBLE_ROWS).forEachIndexed { index, row ->
            val y = topPos + ROWS_Y + index * LINE
            val inside = mouseX >= leftPos + PANEL_X && mouseX <= leftPos + PANEL_X + listWidth() &&
                mouseY >= y - 1 && mouseY <= y + LINE - 2
            if (!inside) return@forEachIndexed
            if (selected != row.word) {
                selected = row.word
                send(DeskAction.PRICE, row.word, InkTier.COMMON)
                rebuild()
            }
            when (tab) {
                DeskTab.WRITE_BOOK ->
                    if (row.inArchive > 0) send(DeskAction.COMPOSE_FROM_ARCHIVE, row.word, InkTier.COMMON)
                DeskTab.ARCHIVE -> send(DeskAction.WITHDRAW, row.word, InkTier.COMMON)
                DeskTab.WRITE_PAGE -> Unit
            }
            return true
        }
        return false
    }

    override fun mouseDragged(
        event: net.minecraft.client.input.MouseButtonEvent,
        dragX: Double,
        dragY: Double,
    ): Boolean {
        if (dragFrom != null) {
            if (kotlin.math.abs(dragX) + kotlin.math.abs(dragY) > DRAG_SLOP) dragMoved = true
            return true
        }
        return super.mouseDragged(event, dragX, dragY)
    }

    /** Dropped elsewhere it is a reorder; barely moved it is the click that takes the page back. */
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

    override fun mouseScrolled(mouseX: Double, mouseY: Double, deltaX: Double, deltaY: Double): Boolean {
        val most = (rows().size - VISIBLE_ROWS).coerceAtLeast(0)
        scroll = (scroll - deltaY.toInt()).coerceIn(0, most)
        return true
    }

    private fun affordable(paper: InkTier): Boolean {
        val (inkTier, units) = DeskModel.priceFor(selected, paper) ?: return true
        return DeskModel.ink(inkTier) >= units
    }

    private fun send(action: DeskAction, word: Identifier?, paper: InkTier, title: String = "") {
        ClientDeskNetwork.send(DeskCommandPayload(action, word, paper, index = -1, target = -1, title = title))
    }

    private fun tint(tier: InkTier): Int = AgeFluids.INKS[tier]?.tint ?: TEXT

    private fun paperIcon(tier: InkTier): ItemStack = when (tier) {
        InkTier.COMMON -> ItemStack(Items.PAPER)
        InkTier.FINE -> ItemStack(AgeContent.FINE_PAPER)
        InkTier.MASTERWORK -> ItemStack(AgeContent.MASTERWORK_PAPER)
    }

    private fun paperGlyph(tier: InkTier): String = when (tier) {
        InkTier.COMMON -> "I"
        InkTier.FINE -> "II"
        InkTier.MASTERWORK -> "III"
    }

    private fun inkGlyph(tier: InkTier): String = when (tier) {
        InkTier.COMMON -> "i"
        InkTier.FINE -> "ii"
        InkTier.MASTERWORK -> "iii"
    }

    private fun tabKey(entry: DeskTab) = "container.agesandtheart.writers_desk.${entry.title}"

    companion object {
        /** A double chest exactly, so the player's half is the one they already know. */
        const val WIDTH = 176
        const val HEIGHT = 222

        /** Where the chest texture splits: six rows of slots plus the header. */
        private const val CHEST_TOP = 6 * 18 + 17
        private const val CHEST_BOTTOM = 96

        private val CHEST: Identifier =
            Identifier.withDefaultNamespace("textures/gui/container/generic_54.png")

        private val SELECTED_TABS = (1..3).map {
            Identifier.withDefaultNamespace("container/creative_inventory/tab_top_selected_$it")
        }
        private val UNSELECTED_TABS = (1..3).map {
            Identifier.withDefaultNamespace("container/creative_inventory/tab_top_unselected_$it")
        }

        // The creative inventory's own numbers.
        private const val TAB_WIDTH = 26
        private const val TAB_HEIGHT = 32
        private const val TAB_SPACING = 27
        private const val TAB_LIFT = 28
        private const val TAB_ICON_INSET = 5
        private const val TAB_ICON_TOP = 9

        private const val PANEL_X = 8
        private const val PANEL_WIDTH = 160
        private const val SEARCH_Y = 20
        private const val ROWS_Y = 38
        private const val LINE = 12
        private const val VISIBLE_ROWS = 5
        private const val COUNT_INSET = 20

        private const val BUTTON_Y = 104
        private const val PAPER_BUTTON = 30

        /** The book tab is two columns: words you could add on the left, the sentence on the right. */
        private const val HALF_WIDTH = 76
        private const val COMPOSE_X = PANEL_X + HALF_WIDTH + 8
        private const val COMPOSE_HEADER_Y = 26
        private const val ORDINAL_WIDTH = 12

        private const val NAME_Y = 104
        private const val NAME_WIDTH = 48
        private const val BIND_WIDTH = 24
        private const val DRAG_SLOP = 3.0

        // The wing overlaps the main panel by its border so the two read as one shape.
        private const val WING_WIDTH = 46
        private const val WING_OVERLAP = 4
        private const val WING_Y = 16
        private const val WING_HEIGHT = 106
        private const val WING_PAD = 5
        private const val BAR_WIDTH = 9
        private const val BAR_GAP = 4
        private const val BAR_HEIGHT = 52
        private const val PAPER_TOP = 62
        private const val PAPER_LINE = 14
        private const val PAPER_COUNT_X = 19

        // Sampled from generic_54.png rather than guessed.
        private val PANEL = 0xFFC6C6C6.toInt()
        private val SLOT = 0xFF8B8B8B.toInt()
        private val OUTLINE = 0xFF000000.toInt()
        private val HIGHLIGHT = 0xFFFFFFFF.toInt()
        private val SHADOW = 0xFF555555.toInt()
        private val TEXT = 0xFF404040.toInt()
        private val FAINT = 0xFF808080.toInt()
        private val SHORT = 0xFF8B2E2E.toInt()
        private val SELECTION = 0x60000000
    }
}

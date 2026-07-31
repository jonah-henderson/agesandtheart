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
    SUPPLIES("supplies", { ItemStack(Items.CHEST) }),
    ;

    /** Only the supplies tab shows the player their own inventory; the rest need the room. */
    val showsInventory: Boolean get() = this == SUPPLIES
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

    /** Only the supplies tab has an inventory to label; elsewhere the words go where the label was. */
    private fun placeInventoryLabel() {
        inventoryLabelY = if (tab.showsInventory) HEIGHT - 94 else OFFSCREEN
    }

    override fun init() {
        super.init()
        // The menu opens on the archive tab, and the server has to agree about which slots exist.
        menu.openTab = tab.ordinal
        send(DeskAction.SET_TAB, null, InkTier.COMMON, index = tab.ordinal)
        placeInventoryLabel()
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

        // Unselected tabs first so the panel covers their base, exactly as the creative screen does.
        DeskTab.entries.filter { it != tab }.forEach { extractTab(graphics, it, mouseX, mouseY) }

        graphics.blit(RenderPipelines.GUI_TEXTURED, CHEST, leftPos, topPos, 0f, 0f, WIDTH, CHEST_TOP, 256, 256)
        graphics.blit(
            RenderPipelines.GUI_TEXTURED, CHEST,
            leftPos, topPos + CHEST_TOP, 0f, 126f, WIDTH, CHEST_BOTTOM, 256, 256,
        )
        // Paint out the chest's own slot grid; ours is not six rows. The lower grid stays only on the
        // supplies tab, which is the one that shows the player their inventory.
        graphics.fill(leftPos + 7, topPos + 17, leftPos + WIDTH - 7, topPos + CHEST_TOP, PANEL)
        if (!tab.showsInventory) {
            graphics.fill(leftPos + 7, topPos + CHEST_TOP, leftPos + WIDTH - 7, topPos + CONTENT_BOTTOM, PANEL)
        }

        extractStockWing(graphics, mouseX, mouseY)
        if (tab != DeskTab.SUPPLIES) extractRows(graphics)
        if (tab == DeskTab.WRITE_PAGE || tab == DeskTab.WRITE_BOOK) extractPrices(graphics)
        if (tab == DeskTab.WRITE_BOOK) {
            extractComposition(graphics)
            extractBindingHint(graphics)
        }
        // The selected tab last, so it sits proud of the panel the others tuck behind.
        extractTab(graphics, tab, mouseX, mouseY)
    }

    /**
     * Leather, ghosted into the empty binding slot.
     *
     * The item itself rather than a sprite of one: `getNoItemIcon` wants the GUI atlas and item textures
     * live on another, and drawing the real thing means a resource pack restyling leather restyles this
     * too. It cycles the binding tag rather than naming leather, so a pack that allows something else
     * shows what it allows.
     */
    private fun extractBindingHint(graphics: GuiGraphicsExtractor) {
        if (!menu.getSlot(BINDING_SLOT).item.isEmpty) return
        val showing = BINDINGS[((System.currentTimeMillis() / BINDING_CYCLE_MS) % BINDINGS.size).toInt()]
        val x = leftPos + BINDING_SLOT_X
        val y = topPos + BINDING_SLOT_Y
        // Vanilla's own ghosting, values and order included: darken, draw, then wash out.
        graphics.fill(x, y, x + ICON, y + ICON, GHOST_UNDER)
        graphics.fakeItem(showing, x, y)
        graphics.fill(x, y, x + ICON, y + ICON, GHOST_OVER)
    }

    private fun extractTab(graphics: GuiGraphicsExtractor, entry: DeskTab, mouseX: Int, mouseY: Int) {
        val index = entry.ordinal
        val x = leftPos + index * TAB_SPACING
        val y = topPos - TAB_LIFT
        val sprite = if (entry == tab) SELECTED_TABS[index] else UNSELECTED_TABS[index]
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, TAB_WIDTH, TAB_HEIGHT)

        val iconX = x + TAB_ICON_INSET
        val iconY = y + TAB_ICON_TOP
        val pop = popScale(entry)
        if (pop == null) {
            graphics.item(entry.icon(), iconX, iconY)
        } else {
            // Vanilla's own squash: narrower as it stretches, so it reads as a bounce rather than a zoom.
            graphics.pose().pushMatrix()
            graphics.pose().translate(iconX + HALF_ICON, iconY + HALF_ICON)
            graphics.pose().scale(1f / pop, (pop + 1f) / 2f)
            graphics.pose().translate(-(iconX + HALF_ICON), -(iconY + HALF_ICON))
            graphics.item(entry.icon(), iconX, iconY)
            graphics.pose().popMatrix()
        }
        if (mouseX in x..(x + TAB_WIDTH) && mouseY in y..(y + TAB_HEIGHT)) {
            graphics.setTooltipForNextFrame(Component.translatable(tabKey(entry)), mouseX, mouseY)
        }
    }

    /** How far through a pop the archive icon is, or null if it is not popping. */
    private fun popScale(entry: DeskTab): Float? {
        if (entry != DeskTab.ARCHIVE) return null
        val since = System.currentTimeMillis() - DeskModel.archiveGrewAt
        if (DeskModel.archiveGrewAt == 0L || since > POP_MS) return null
        return 1f + (1f - since.toFloat() / POP_MS) * POP_DEPTH
    }

    /**
     * The stocks, on a panel hanging off the left edge.
     *
     * Outside the main panel because they belong to every tab — the tab decides what you are doing, the
     * wing says what you have to do it with, and that should not move when you switch.
     */
    private fun extractStockWing(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val x = leftPos - WING_WIDTH
        val y = topPos
        wingPanel(graphics, x, y, WING_WIDTH, WING_HEIGHT)

        InkTier.entries.forEachIndexed { index, tier ->
            val barX = x + WING_PAD + index * (BAR_WIDTH + BAR_GAP)
            val barY = y + BAR_TOP
            val held = DeskModel.ink(tier)
            val filled = (BAR_HEIGHT * held.toDouble() / DeskModel.inkCapacity().coerceAtLeast(1)).toInt()
            gauge(graphics, barX, barY, filled, tint(tier))
            if (mouseX in barX..(barX + BAR_WIDTH) && mouseY in barY..(barY + BAR_HEIGHT)) {
                graphics.setTooltipForNextFrame(inkTooltip(tier, held), mouseX, mouseY)
            }
        }

        InkTier.entries.forEachIndexed { index, tier ->
            val rowY = y + PAPER_TOP + index * PAPER_LINE
            graphics.item(paperIcon(tier), x + WING_PAD, rowY)
            // Right-aligned, so the digits line up rather than the labels.
            val count = "${DeskModel.paper(tier)}"
            val right = x + WING_WIDTH - WING_PAD
            graphics.text(font, count, right - font.width(count), rowY + PAPER_TEXT_DROP, TEXT, false)
        }
    }

    /**
     * A recessed gauge, filled from the bottom — the brewing stand's fuel groove rather than a flat bar.
     *
     * The inset is what makes it read as a gauge: dark on the top and left, light on the bottom and
     * right, which is the panel's own bevel turned inside out. Colours sampled from `brewing_stand.png`.
     */
    private fun gauge(graphics: GuiGraphicsExtractor, x: Int, y: Int, filled: Int, colour: Int) {
        graphics.fill(x - 1, y - 1, x + BAR_WIDTH + 1, y + BAR_HEIGHT + 1, GROOVE_EDGE)
        graphics.fill(x, y, x + BAR_WIDTH + 1, y + BAR_HEIGHT + 1, GROOVE_LIP)
        graphics.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, GROOVE)
        if (filled > 0) {
            graphics.fill(x, y + BAR_HEIGHT - filled, x + BAR_WIDTH, y + BAR_HEIGHT, colour)
        }
    }

    /**
     * The wing, bordered on three sides only.
     *
     * Its right edge runs into the main panel, so giving it one there would draw a seam through what
     * should read as a single shape.
     */
    private fun wingPanel(graphics: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int) {
        graphics.fill(x, y, x + width, y + height, OUTLINE)
        graphics.fill(x + 1, y + 1, x + width, y + height - 1, HIGHLIGHT)
        graphics.fill(x + 3, y + height - 3, x + width, y + height - 1, SHADOW)
        graphics.fill(x + 3, y + 3, x + width, y + height - 3, PANEL)
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

    /**
     * How many rows fit, which differs per tab because each keeps a different amount of room below.
     *
     * The player's inventory is hidden everywhere but the supplies tab, and this is what that bought.
     */
    private fun visibleRows(): Int = when (tab) {
        DeskTab.ARCHIVE -> ARCHIVE_ROWS
        DeskTab.WRITE_PAGE -> PAGE_ROWS
        DeskTab.WRITE_BOOK -> BOOK_ROWS
        DeskTab.SUPPLIES -> 0
    }

    private fun rows(): List<WordRow> = when (tab) {
        DeskTab.ARCHIVE -> DeskModel.archiveRows(filter)
        else -> DeskModel.writableRows(filter)
    }

    private fun extractRows(graphics: GuiGraphicsExtractor) {
        rows().drop(scroll).take(visibleRows()).forEachIndexed { index, row ->
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
        words.take(visibleRows()).forEachIndexed { index, word ->
            val y = topPos + ROWS_Y + index * LINE
            graphics.text(font, "${index + 1}. ", leftPos + COMPOSE_X, y, FAINT, false)
            graphics.text(font, WordNames.readable(word), leftPos + COMPOSE_X + ORDINAL_WIDTH, y, TEXT, false)
        }
    }

    private fun composedCellAt(mouseX: Double, mouseY: Double): Int? {
        if (tab != DeskTab.WRITE_BOOK) return null
        DeskModel.composing().indices.take(visibleRows()).forEach { index ->
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
                    menu.openTab = entry.ordinal
                    send(DeskAction.SET_TAB, null, InkTier.COMMON, index = entry.ordinal)
                    placeInventoryLabel()
                    rebuild()
                }
                return true
            }
        }
        return false
    }

    private fun clickRow(mouseX: Double, mouseY: Double): Boolean {
        rows().drop(scroll).take(visibleRows()).forEachIndexed { index, row ->
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
                DeskTab.WRITE_PAGE, DeskTab.SUPPLIES -> Unit
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
        val most = (rows().size - visibleRows()).coerceAtLeast(0)
        scroll = (scroll - deltaY.toInt()).coerceIn(0, most)
        return true
    }

    private fun affordable(paper: InkTier): Boolean {
        val (inkTier, units) = DeskModel.priceFor(selected, paper) ?: return true
        return DeskModel.ink(inkTier) >= units
    }

    private fun send(
        action: DeskAction,
        word: Identifier?,
        paper: InkTier,
        title: String = "",
        index: Int = -1,
    ) {
        ClientDeskNetwork.send(DeskCommandPayload(action, word, paper, index, target = -1, title = title))
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
        private const val COUNT_INSET = 20

        private const val ARCHIVE_ROWS = 14
        private const val PAGE_ROWS = 12
        private const val BOOK_ROWS = 9

        private const val BUTTON_Y = 192
        private const val PAPER_BUTTON = 30

        /** The book tab is two columns: words you could add on the left, the sentence on the right. */
        private const val HALF_WIDTH = 76
        private const val COMPOSE_X = PANEL_X + HALF_WIDTH + 8
        private const val COMPOSE_HEADER_Y = 26
        private const val ORDINAL_WIDTH = 12

        private const val NAME_Y = 174
        private const val NAME_WIDTH = 48
        private const val BIND_WIDTH = 24
        private const val DRAG_SLOP = 3.0

        /** Where the panel's inner area ends when the player's inventory is hidden. */
        private const val CONTENT_BOTTOM = 212

        /** Far enough down that the label is simply not drawn. */
        private const val OFFSCREEN = 10_000

        /** Matches the menu's slot order and coordinates. */
        private const val BINDING_SLOT = 1
        private const val BINDING_SLOT_X = 110
        private const val BINDING_SLOT_Y = 152

        private const val ICON = 16
        private const val HALF_ICON = 8f

        /**
         * What may bind a book, cycled the way a recipe viewer cycles a tag — so a pack that allows
         * something else shows what it allows rather than always promising leather.
         */
        private val BINDINGS = listOf(ItemStack(Items.LEATHER))
        private const val BINDING_CYCLE_MS = 1000L

        // Vanilla's ghost-slot wash, taken from GhostSlots verbatim.
        private const val GHOST_UNDER = 822018048
        private const val GHOST_OVER = 822083583

        /** A pop of a quarter, decaying over a fifth of a second. */
        private const val POP_MS = 200f
        private const val POP_DEPTH = 0.25f

        // The wing overlaps the main panel by its border so the two read as one shape.
        // Top of the panel down to just above the Inventory label, and running into the panel's left edge.
        private const val WING_WIDTH = 46
        private const val WING_HEIGHT = 124
        private const val WING_PAD = 6
        private const val BAR_TOP = 12
        private const val BAR_WIDTH = 9
        private const val BAR_GAP = 4
        private const val BAR_HEIGHT = 58
        private const val PAPER_TOP = 78
        private const val PAPER_LINE = 14
        private const val PAPER_TEXT_DROP = 5

        // Sampled from generic_54.png rather than guessed.
        private val PANEL = 0xFFC6C6C6.toInt()
        private val SLOT = 0xFF8B8B8B.toInt()
        private val OUTLINE = 0xFF000000.toInt()
        private val HIGHLIGHT = 0xFFFFFFFF.toInt()
        private val SHADOW = 0xFF555555.toInt()

        // The brewing stand's fuel groove: dark lip above and left, light below and right.
        private val GROOVE_EDGE = 0xFF373737.toInt()
        private val GROOVE_LIP = 0xFFFFFFFF.toInt()
        private val GROOVE = 0xFF8B8B8B.toInt()
        private val TEXT = 0xFF404040.toInt()
        private val FAINT = 0xFF808080.toInt()
        private val SHORT = 0xFF8B2E2E.toInt()
        private val SELECTION = 0x60000000
    }
}

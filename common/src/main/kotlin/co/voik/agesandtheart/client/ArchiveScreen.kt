package co.voik.agesandtheart.client

import co.voik.agesandtheart.client.ui.scrollChildUnder
import co.voik.agesandtheart.age.word.WordNames
import co.voik.agesandtheart.client.ui.DecorationWidget
import co.voik.agesandtheart.client.ui.FlexColumn
import co.voik.agesandtheart.client.ui.LabelledList
import co.voik.agesandtheart.client.ui.OrderButton
import co.voik.agesandtheart.client.ui.Palette
import co.voik.agesandtheart.client.ui.PanelSurface
import co.voik.agesandtheart.client.ui.PlayerInventoryView
import co.voik.agesandtheart.client.ui.Rect
import co.voik.agesandtheart.client.ui.RowAction
import co.voik.agesandtheart.desk.ArchiveMenu
import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.desk.ArchivePricesPayload
import co.voik.agesandtheart.desk.ArchiveStockPayload
import co.voik.agesandtheart.desk.ArchiveSyncPayload
import co.voik.agesandtheart.desk.ArchiveWithdrawPayload
import co.voik.agesandtheart.desk.ArchiveWritePayload
import co.voik.agesandtheart.desk.ArchiveWriting
import co.voik.agesandtheart.desk.PageArchive
import co.voik.agesandtheart.desk.PagePrice
import co.voik.agesandtheart.desk.WritingObstacle
import co.voik.agesandtheart.desk.WritingStock
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Inventory

/** One word an archive holds, and how many pages of it. */
data class ArchiveRow(override val word: Identifier, override val readable: String, val count: Int) : OrderedWord {
    override val stocked: Int get() = count
}

/**
 * An archive's pages, searched, above the player's inventory.
 *
 * Shift-clicking a page or a notebook in the inventory files it. Each row says how many pages of the word are
 * filed, hands them back — one, or a stackful with shift held — and writes another, paid for at the nearest
 * writer's desk. The write button is always there and says why when it is disabled, so a player finds out what a
 * desk is for before they have one.
 *
 * **Filed or Known**: the pages the archive holds, or every word the player has learned.
 */
class ArchiveScreen(menu: ArchiveMenu, inventory: Inventory, title: Component) :
    AbstractContainerScreen<ArchiveMenu>(menu, inventory, title, ArchiveMenu.PANEL_WIDTH, ArchiveMenu.PANEL_HEIGHT) {

    private lateinit var search: EditBox
    private lateinit var modeButton: CycleButton<Boolean>
    private lateinit var list: LabelledList<ArchiveRow>
    private lateinit var orderButton: OrderButton<WordOrder>
    private var shown: List<ArchiveRow> = emptyList()
    private var shownIn: WordOrder? = null

    override fun init() {
        super.init()
        inventoryLabelY = ArchiveMenu.INVENTORY_LABEL_Y
        // Level with the mode button's label, which a button centres one pixel low.
        titleLabelY = MODE_TOP + (MODE_HEIGHT - font.lineHeight) / 2 + 1
        val keptFilter = if (::search.isInitialized) search.value else ""

        addRenderableWidget(
            DecorationWidget(PanelSurface.RAISED).also {
                it.setPosition(leftPos, topPos)
                it.setSize(imageWidth, imageHeight)
            },
        )
        addRenderableWidget(
            PlayerInventoryView(
                leftPos + ArchiveMenu.INVENTORY_X,
                topPos + ArchiveMenu.INVENTORY_Y,
                ArchiveMenu.HOTBAR_DROP,
            ),
        )

        search = EditBox(font, 0, 0, 0, LINE, Component.empty())
        search.setHint(translated("search"))
        search.value = keptFilter
        search.setResponder {
            list.resetScroll()
            refresh(force = true)
        }
        val filedLabel = translated("mode.filed")
        val knownLabel = translated("mode.known")
        val modeWidth = maxOf(font.width(filedLabel), font.width(knownLabel)) + MODE_PADDING * 2
        modeButton = CycleButton.booleanBuilder(filedLabel, knownLabel, !showingKnown)
            .withTooltip { filed -> Tooltip.create(translated(if (filed) "filed" else "known")) }
            .displayOnlyValue()
            .create(
                leftPos + imageWidth - INSET - modeWidth,
                topPos + MODE_TOP,
                modeWidth,
                MODE_HEIGHT,
                Component.empty(),
            ) { _, filed -> showMode(known = !filed) }
        addRenderableWidget(modeButton)
        addRenderableWidget(search)
        orderButton = addRenderableWidget(
            WordListOrder.button(LINE, order, ::offeredOrders) { chosen ->
                order = chosen
                list.resetScroll()
                refresh(force = true)
            },
        )
        list = addRenderableWidget(LabelledList(Minecraft.getInstance(), Rect(0, 0, 0, 0)) {})

        val column = FlexColumn(imageWidth - INSET * 2, ArchiveMenu.INVENTORY_LABEL_Y - GAP - CONTENT_TOP)
        column.add(search, height = LINE)
        column.gap(GAP)
        column.fill(list)
        column.setPosition(leftPos + INSET, topPos + CONTENT_TOP)
        column.arrangeElements()
        // The order button takes the right end of the search row, square, just above the list's right edge.
        search.width -= LINE + GAP
        orderButton.setPosition(search.x + search.width + GAP, search.y)

        focused = search
        refresh(force = true)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean =
        super.mouseScrolled(mouseX, mouseY, scrollX, scrollY) || scrollChildUnder(mouseX, mouseY, scrollX, scrollY)

    /** A key belongs to the search box while it has the caret, or `e` would close the screen mid-word. */
    override fun keyPressed(event: KeyEvent): Boolean {
        if (!search.isFocused) return super.keyPressed(event)
        if (search.keyPressed(event)) return true
        return if (event.isEscape) super.keyPressed(event) else true
    }

    /** Forgotten on close, so the next archive opened never shows this one's pages while its own arrive. */
    override fun removed() {
        super.removed()
        held = PageArchive.EMPTY
        prices = emptyMap()
        stock = null
    }

    override fun containerTick() {
        super.containerTick()
        refresh(force = false)
    }

    private fun showMode(known: Boolean) {
        showingKnown = known
        list.resetScroll()
        refresh(force = true)
    }

    /** An empty archive says what it is for, where its pages would be. */
    override fun extractContents(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractContents(graphics, mouseX, mouseY, a)
        if (showingKnown || held.words.isNotEmpty()) return
        graphics.textWithWordWrap(font, translated("hint"), list.x + HINT_INSET, list.y + HINT_INSET, list.width - 2 * HINT_INSET, Palette.FAINT)
    }

    /** By name, by rarity and by count always, and by what each applies to with a grammar guide at the nearest desk. */
    private fun offeredOrders(): List<WordOrder> =
        listOf(WordOrder.ALPHABETICAL, WordOrder.RARITY, WordOrder.STOCKED) + listOfNotNull(WordOrder.APPLIES_TO.takeIf { showsWhereWordsApply })

    private fun refresh(force: Boolean) {
        val needle = search.value
        val words = if (showingKnown) KnownWords.words else held.words
        val rows = words.map { ArchiveRow(it, WordNames.readable(it).string, held.count(it)) }
        val ordering = WordListOrder.stillOffered(order, offeredOrders())
        if (!force && rows == shown && ordering == shownIn) return
        if (ordering != order) orderButton.showing(ordering)
        order = ordering
        shown = rows
        shownIn = ordering
        list.showGrouped(
            WordListOrder.grouped(rows, ordering, needle),
            label = { it.readable },
            count = { row -> row.count.takeIf { it > 0 } },
            key = { it.word },
            actions = listOf(write, takeOut),
            startsCollapsed = ordering == WordOrder.APPLIES_TO && needle.isBlank(),
        )
    }

    /** Whether a page of [word] could be written now, and if not, what stands in the way. */
    private fun obstacleTo(word: Identifier): WritingObstacle? = ArchiveWriting.obstacleTo(prices[word], stock)

    private val write = RowAction<ArchiveRow>(
        glyph = "✎",
        tooltip = { row -> writeTooltip(row.word) },
        enabled = { row -> obstacleTo(row.word) == null },
        act = { row -> sendToServer(ArchiveWritePayload(row.word)) },
    )

    private fun writeTooltip(word: Identifier): Component {
        val price = prices[word]
        return when (val obstacle = obstacleTo(word)) {
            null -> translated("write", inkName(price?.required ?: InkTier.COMMON))
            WritingObstacle.SHORT_OF_INK -> translated(obstacle.key, inkName(price?.required ?: InkTier.COMMON))
            else -> translated(obstacle.key)
        }
    }

    private fun inkName(tier: InkTier): Component =
        Component.translatable("container.agesandtheart.writers_desk.ink.${tier.key}")

    private val takeOut = RowAction<ArchiveRow>(
        glyph = "▼",
        tooltip = { row -> translated(if (row.count > 0) "withdraw" else "none_stored") },
        enabled = { row -> row.count > 0 },
        act = { row ->
            sendToServer(ArchiveWithdrawPayload(row.word, wholeStack = Minecraft.getInstance().hasShiftDown()))
        },
    )

    private fun translated(suffix: String, vararg arguments: Any): Component =
        Component.translatable("container.agesandtheart.archive.$suffix", *arguments)

    companion object {
        /** What the open archive holds, as last sent. One archive screen is open at a time. */
        private var held: PageArchive = PageArchive.EMPTY

        /** What each known word costs to write, as last sent. */
        private var prices: Map<Identifier, PagePrice> = emptyMap()

        /** What the nearest writer's desk holds, or null where none is in range. */
        private var stock: WritingStock? = null

        /** Kept across the screen being rebuilt, as the search text is. */
        private var showingKnown = false

        /** How the list is ordered, kept as the mode is. */
        private var order = WordOrder.ALPHABETICAL

        /** Whether the nearest desk has the grammar guide by it, as last sent. */
        private var showsWhereWordsApply = false

        fun remember(payload: ArchiveSyncPayload) {
            held = payload.pages
        }

        fun remember(payload: ArchivePricesPayload) {
            prices = payload.prices
        }

        fun remember(payload: ArchiveStockPayload) {
            stock = payload.stock
            showsWhereWordsApply = payload.showsWhereWordsApply
        }

        /** The mode button sits on the title line, which is laid out around it. */
        private const val MODE_TOP = 5
        private const val MODE_HEIGHT = 14
        private const val MODE_PADDING = 5

        private const val LINE = 12
        private const val GAP = 4
        private const val INSET = 8
        private const val CONTENT_TOP = MODE_TOP + MODE_HEIGHT + GAP
        private const val HINT_INSET = 2
    }
}

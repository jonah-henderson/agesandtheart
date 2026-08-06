package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.age.word.WordNames
import co.voik.agesandtheart.client.ui.BookWritingWorkSurface
import co.voik.agesandtheart.client.ui.CapsuleGauge
import co.voik.agesandtheart.client.ui.ColourSurface
import co.voik.agesandtheart.client.ui.CountedItem
import co.voik.agesandtheart.client.ui.DecoratedBox
import co.voik.agesandtheart.client.ui.DecorationWidget
import co.voik.agesandtheart.client.ui.Edge
import co.voik.agesandtheart.client.ui.FlexColumn
import co.voik.agesandtheart.client.ui.Insets
import co.voik.agesandtheart.client.ui.LabelledList
import co.voik.agesandtheart.client.ui.Palette
import co.voik.agesandtheart.client.ui.PanelSurface
import co.voik.agesandtheart.client.ui.PlayerInventoryView
import co.voik.agesandtheart.client.ui.Rect
import co.voik.agesandtheart.client.ui.RowAction
import co.voik.agesandtheart.client.ui.SlotView
import co.voik.agesandtheart.client.ui.TabStrip
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.AgeFluids
import co.voik.agesandtheart.content.NotebookItem
import co.voik.agesandtheart.desk.DeskAction
import co.voik.agesandtheart.desk.DeskCapability
import co.voik.agesandtheart.desk.DeskCommandPayload
import co.voik.agesandtheart.desk.DeskSlots
import co.voik.agesandtheart.desk.WritersDeskMenu
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.MultiLineTextWidget
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * The desk, assembled from `client/ui` pieces.
 *
 * Composes and decides; draws nothing itself. Widgets are added **back to front** — the order of [build]
 * is the order they stack.
 */
class WritersDeskScreen(
    menu: WritersDeskMenu,
    inventory: Inventory,
    title: Component,
) : AbstractContainerScreen<WritersDeskMenu>(
    menu, inventory, title, DeskLayout.WIDTH, DeskLayout.HEIGHT,
) {

    private var tab = DeskTab.ARCHIVE
    private var selectedWord: Identifier? = null

    /** Which paper a row's write button spends. The cheapest by default, so nobody wastes the good stuff. */
    private var chosenPaper: InkTier = InkTier.COMMON

    // What the lists are showing, so they are only rebuilt when the answer actually changes.
    private var shownWords: List<WordRow> = emptyList()
    private var shownComposition: List<Identifier> = emptyList()

    private lateinit var layout: DeskLayout
    private lateinit var tabs: TabStrip<DeskTab>
    private lateinit var wordList: LabelledList<WordRow>
    private lateinit var composition: BookWritingWorkSurface<Identifier>
    private lateinit var search: EditBox
    private lateinit var ageName: EditBox
    private lateinit var paperButtons: List<Button>
    private lateinit var bindButton: Button
    private lateinit var reading: MultiLineTextWidget
    private lateinit var columns: Map<DeskTab, FlexColumn>
    private lateinit var bindingRow: LinearLayout

    /**
     * Widgets only some tabs show, each with the rule that decides.
     *
     * Registered where a widget is added, so the two cannot drift apart.
     */
    private val perTab = mutableListOf<Pair<AbstractWidget, (DeskTab) -> Boolean>>()

    override fun init() {
        super.init()
        // Vanilla centres the panel alone, but the tabs stand above it — so the whole thing sat high enough
        // to clip them off the top. What has to be centred is panel *plus* lift, and moving down by half
        // the lift is the same statement.
        topPos += TabStrip.LIFT / 2
        layout = DeskLayout(leftPos, topPos)
        build()
        showTab()
        // The server has to agree about which slots exist.
        menu.openTab = tab.ordinal
        send(DeskAction.SET_TAB, index = tab.ordinal)
    }

    /** Back to front. Everything below is added in the order it stacks. */
    private fun build() {
        val keptFilter = if (::search.isInitialized) search.value else ""
        clearWidgets()
        // In step with `clearWidgets`, or a resize would leave rules pointing at widgets no longer shown.
        perTab.clear()

        tabs = TabStrip(
            layout.tabsX, layout.tabsY, DeskTab.entries, tab,
            icon = { it.icon() },
            label = { it.title },
            onSelect = ::openTab,
        )
        tabs.pulsedAt = { if (it == DeskTab.ARCHIVE) DeskModel.archiveGrewAt else 0L }

        // Unselected tabs tuck under the panel, so they go on before it.
        addRenderableWidget(tabs.backdrop)
        addRenderableWidget(surface(PanelSurface.RAISED, layout.panel))
        addWing()
        addSlots()
        // The strip itself last of the chrome, so the selected tab sits proud of the panel.
        addRenderableWidget(tabs)

        addControls(keptFilter)
    }

    private fun addWing() {
        val gauges = LinearLayout.horizontal().spacing(GAUGE_GAP)
        InkTier.entries.forEach { tier ->
            gauges.addChild(
                CapsuleGauge(
                    GAUGE_WIDTH, GAUGE_HEIGHT,
                    reading = { DeskModel.ink(tier).toFloat() / DeskModel.inkCapacity().coerceAtLeast(1) },
                    colour = { AgeFluids.INKS[tier]?.tint ?: Palette.TEXT },
                    tooltip = { inkTooltip(tier) },
                ),
            )
        }

        val stocks = LinearLayout.vertical()
        InkTier.entries.forEach { tier ->
            stocks.addChild(
                CountedItem(STOCK_WIDTH, STOCK_LINE, icon = { paperIcon(tier) }, count = { DeskModel.paper(tier) }),
            )
        }
        stocks.addChild(
            CountedItem(STOCK_WIDTH, STOCK_LINE, icon = { BINDING_ICON }, count = { DeskModel.binding() }),
        )

        val contents = LinearLayout.vertical().spacing(GROUP_GAP)
        contents.addChild(gauges)
        contents.addChild(stocks)

        // Asymmetric because the border eats the left edge but not the open right, and because the gauges
        // want more room above them than the stocks want below. These four numbers, the two group sizes and
        // the spacings are the whole of the wing's layout — its width and height fall out of them.
        val wing = DecoratedBox(PanelSurface(openOn = Edge.RIGHT), WING_PADDING)
        wing.holding(contents)
        wing.arrangeElements()
        // Overlapping the panel's border rather than painting over it: the wing's own top and bottom edges
        // then run the whole way across, so the two borders meet instead of stopping short of each other.
        wing.sized(wing.width + Palette.BORDER, wing.height)
        wing.setPosition(layout.panel.x + Palette.BORDER - wing.width, layout.panel.y)
        wing.arrangeElements()
        wing.visitWidgets(::addRenderableWidget)
    }

    private fun addSlots() {
        addShownOn(
            PlayerInventoryView(
                layout.playerInventory.x, layout.playerInventory.y, DeskSlots.HOTBAR_DROP,
            ),
        ) { it.showsInventory }

        // A recess per slot, shown exactly when the menu makes that slot active.
        DeskTab.entries.forEach { owner ->
            layout.slotsOn(owner).forEach { area ->
                addShownOn(SlotView(area.x, area.y)) { it == owner }
            }
        }

    }

    /** Adds a widget and says in the same breath which tabs it belongs to. */
    private fun <T : AbstractWidget> addShownOn(widget: T, showsOn: (DeskTab) -> Boolean): T {
        addRenderableWidget(widget)
        perTab += widget to showsOn
        return widget
    }

    /** The word list and its search: the archive, and the surface you lay pages onto from it. */
    private fun lists(tab: DeskTab) = tab != DeskTab.BIND

    /** Writing a page is the archive's, now that it shows every word you know. */
    private fun writes(tab: DeskTab) = tab == DeskTab.ARCHIVE

    private fun binds(tab: DeskTab) = tab == DeskTab.BIND

    /** The pages laid out — the work surface's own tab, and read back on the bind screen. */
    private fun composes(tab: DeskTab) = tab == DeskTab.WRITE_BOOK

    private fun addControls(keptFilter: String) {
        // Sizes only — where any of this goes is the columns' business, below.
        search = EditBox(font, 0, 0, 0, LINE, Component.empty())
        search.setHint(translated("search"))
        search.value = keptFilter
        search.setResponder {
            wordList.resetScroll()
            refreshWords(force = true)
        }
        addShownOn(search, ::lists)

        wordList = addShownOn(
            LabelledList(Minecraft.getInstance(), Rect(0, 0, 0, 0), ::chooseWord), ::lists,
        )

        composition = addShownOn(
            BookWritingWorkSurface(
                Rect(0, 0, 0, 0),
                columns = DeskLayout.SURFACE_COLUMNS,
                cellHeight = DeskLayout.CELL_HEIGHT,
                gutterHeight = DeskLayout.GUTTER_HEIGHT,
                script = { KnownWords.scriptLines(it) },
                translation = { WordNames.readable(it).string },
                onReorder = { from, onto -> send(DeskAction.MOVE_IN_BOOK, index = from, target = onto) },
                onRemove = { index -> send(DeskAction.RETURN_TO_ARCHIVE, index = index) },
                capacity = { DeskModel.pageLimit() },
                quarrel = ::quarrelAt,
            ),
            ::composes,
        )

        // **A toggle, not three write buttons.** Which word to write is a row's business now, so what is
        // left here is which paper it is written on — better paper buys the same word for less ink and can
        // never unlock one (`WriteCost`).
        paperButtons = InkTier.entries.map { paper ->
            val button = Button.builder(Component.literal(paperGlyph(paper))) { chosenPaper = paper }
                .bounds(0, 0, PAPER_BUTTON_WIDTH, LINE + 2).build()
            addShownOn(button, ::writes)
        }

        // **Scratch mode** (design §4.3.1): the row of pages said back as a sentence, which is the half
        // that makes attachment visible. It comes from the server — reading one takes the whole corpus —
        // and it is the same `Readout` the bound book carries, so the desk and the book cannot disagree.
        reading = addShownOn(
            MultiLineTextWidget(Component.empty(), font).setMaxWidth(layout.content(DeskTab.BIND).width),
            ::binds,
        )

        ageName = EditBox(font, 0, 0, NAME_WIDTH, LINE, Component.empty())
        ageName.setHint(translated("name"))
        ageName.setMaxLength(DeskCommandPayload.MAX_TITLE)
        addShownOn(ageName, ::binds)

        bindButton = addShownOn(
            Button.builder(translated("bind")) {
                send(DeskAction.FINALISE, title = ageName.value)
            }.bounds(0, 0, BIND_WIDTH, LINE).build(),
            ::binds,
        )

        columns = DeskTab.entries.associateWith(::columnFor)
        bindingRow = LinearLayout.horizontal().spacing(GAP).apply {
            addChild(ageName)
            addChild(bindButton)
        }
    }

    /**
     * How a tab stacks, and the only statement of it.
     *
     * No position appears here — a column is told its room and which child stretches, and works the rest
     * out. Which is why the same four widgets can sit at four different heights without anyone writing down
     * where any of them stops.
     */
    private fun columnFor(entry: DeskTab): FlexColumn {
        val room = layout.content(entry)
        val column = FlexColumn(room.width, room.height)
        when (entry) {
            // Every word you know, and the paper to write one on — the page tab's whole job, absorbed.
            DeskTab.ARCHIVE -> {
                column.add(search, height = LINE)
                column.gap(GAP)
                column.fill(wordList)
                column.gap(GAP)
                column.add(paperRow())
                column.gap(LINE) // the ink price under each button, drawn rather than a widget
            }
            DeskTab.WRITE_BOOK -> {
                column.add(search, height = LINE)
                column.gap(GAP)
                // Short, because the surface is what this tab is for.
                column.add(wordList, height = BOOK_WORD_LIST_HEIGHT)
                column.gap(GAP)
                column.gap(LINE) // the "n / limit" header, drawn rather than a widget
                column.fill(composition)
            }
            // Nothing to arrange but the sentence: the name, the button and the slot are anchored to the
            // panel's foot by `DeskLayout.bindingRow`, as they always were.
            DeskTab.BIND -> column.fill(reading)
        }
        column.setPosition(room.x, room.y)
        return column
    }

    private fun paperRow(): LinearLayout =
        LinearLayout.horizontal().spacing(GAP).apply { paperButtons.forEach(::addChild) }

    private fun surface(decoration: co.voik.agesandtheart.client.ui.Decoration, at: Rect): AbstractWidget =
        DecorationWidget(decoration).also {
            it.setPosition(at.x, at.y)
            it.setSize(at.width, at.height)
        }

    /**
     * Applies every registered rule, so nothing can be shown by having been forgotten, then lets this
     * tab's column place what it shows.
     *
     * Arranging rather than rebuilding: the widgets are shared between tabs and only their arrangement
     * differs, and rebuilding would mutate the widget list that the dispatching click is iterating.
     */
    private fun showTab() {
        inventoryLabelY = layout.inventoryLabelY(tab)
        perTab.forEach { (widget, showsOn) -> widget.visible = showsOn(tab) }
        // Keys reach a widget only through the screen's focus, so an unfocused search box lets `e` fall
        // through to "close the inventory". The creative screen focuses its search for the same reason.
        if (search.visible) focused = search
        columns[tab]?.arrangeElements()
        if (tab == DeskTab.WRITE_BOOK) {
            val row = layout.bindingRow()
            bindingRow.arrangeElements()
            bindingRow.setPosition(row.x, row.y)
        }

        refreshWords(force = true)
        refreshComposition(force = true)
        refreshReading()
    }

    private fun openTab(entry: DeskTab) {
        tab = entry
        menu.openTab = entry.ordinal
        send(DeskAction.SET_TAB, index = entry.ordinal)
        showTab()
    }

    /**
     * A page held in hand, dropped onto the work surface.
     *
     * Checked before the widgets get the click, because the surface would otherwise read it as the start of
     * a drag. Carrying an item is vanilla's own gesture — picked up from the player's inventory or from the
     * archive — so laying one out is that same motion continued rather than a second drag mechanic.
     */
    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (composition.visible && NotebookItem.isPage(menu.carried)) {
            val at = composition.insertionAt(event.x, event.y)
            if (at != null) {
                if (!composition.isFull) send(DeskAction.COMPOSE_FROM_HAND, index = at)
                return true
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    /**
     * A key pressed while a box has the caret belongs to the box, whether or not it wanted it.
     *
     * Without this, `e` reaches `AbstractContainerScreen`'s "close the inventory" and the desk shuts
     * mid-word: an [EditBox] returns false from `keyPressed` for an ordinary letter, because the letter
     * arrives as a character rather than as a key. Vanilla's creative search box is guarded exactly so.
     */
    override fun keyPressed(event: KeyEvent): Boolean {
        val typingInto = boxWithTheCaret() ?: return super.keyPressed(event)
        if (typingInto.keyPressed(event)) return true
        return if (event.isEscape) super.keyPressed(event) else true
    }

    private fun boxWithTheCaret(): EditBox? =
        listOf(search, ageName).firstOrNull { it.visible && it.isFocused }

    override fun containerTick() {
        super.containerTick()
        refreshWords(force = false)
        refreshComposition(force = false)
        refreshReading()
        paperButtons.forEachIndexed { index, button ->
            // Dark for the chosen one, so the row of three reads as a setting rather than three actions.
            button.active = InkTier.entries[index] != chosenPaper
        }
    }

    private fun refreshWords(force: Boolean) {
        if (!wordList.visible) return
        val rows = DeskModel.knownRows(search.value)
        if (!force && rows == shownWords) return
        shownWords = rows
        wordList.show(
            rows,
            label = { it.readable },
            count = { it.inArchive },
            key = { it.word },
            actions = if (tab == DeskTab.ARCHIVE) rowActions() else emptyList(),
        )
    }

    /** The sentence, whenever the pages under it move. */
    private fun refreshReading() {
        if (!reading.visible) return
        val said = DeskModel.reading()
        reading.message = if (said.isEmpty()) translated("nothing_written") else Component.literal(said)
    }

    /**
     * What the page at [index] is arguing with, or null where nothing is.
     *
     * By position rather than by word, because the same word laid twice is two pages and the quarrel may
     * be about only one of them — the server names the words, and the first laid page carrying that word is
     * the one the argument is about.
     */
    private fun quarrelAt(index: Int): Component? {
        val laid = DeskModel.composing().getOrNull(index) ?: return null
        val quarrel = DeskModel.quarrels().firstOrNull { it.word == laid } ?: return null
        val other = WordNames.readable(quarrel.against)
        return if (quarrel.word == quarrel.against) {
            Component.translatable("container.agesandtheart.writers_desk.quarrel_alone")
        } else {
            Component.translatable("container.agesandtheart.writers_desk.quarrel", other)
        }
    }

    private fun refreshComposition(force: Boolean) {
        if (!composition.visible) return
        val words = DeskModel.composing()
        if (!force && words == shownComposition) return
        shownComposition = words
        composition.show(words)
    }

    /**
     * What a row of the archive offers to do with its word: write one, lay one out, take one away.
     *
     * Each says why it is dark rather than vanishing when it cannot act, which is the difference between a
     * button that is not ready and a feature you have not found.
     */
    private fun rowActions(): List<RowAction<WordRow>> = listOf(
        RowAction(
            glyph = "+",
            tooltip = { row -> writeTooltip(row) },
            enabled = { canWrite(chosenPaper) },
            act = { row -> send(DeskAction.WRITE_TO_ARCHIVE, word = row.word, paper = chosenPaper) },
        ),
        RowAction(
            glyph = "»",
            tooltip = { row ->
                if (row.inArchive > 0) translated("to_book") else translated("to_book_none")
            },
            enabled = { it.inArchive > 0 },
            act = { row -> send(DeskAction.COMPOSE_FROM_ARCHIVE, word = row.word) },
        ),
        RowAction(
            glyph = "\u25bc",
            tooltip = { row ->
                if (row.inArchive > 0) translated("withdraw") else translated("withdraw_none")
            },
            enabled = { it.inArchive > 0 },
            act = { row -> send(DeskAction.WITHDRAW, word = row.word) },
        ),
    )

    /**
     * What writing one would cost, or why it cannot be paid.
     *
     * A word nobody has quoted yet is asked about **here**, on the hover, rather than only on selection —
     * "what does this cost" is the question a hover is asking, and a row that answered it only after being
     * clicked read as a tooltip that worked sometimes. One request per word per session
     * ([DeskModel.startAsking]), so the corpus still never crosses the wire.
     */
    private fun writeTooltip(row: WordRow): Component {
        val price = DeskModel.priceFor(row.word, chosenPaper)
            ?: return quoteFor(row.word)
        val (inkTier, units) = price
        val ink = Component.translatable("ink.agesandtheart.${inkTier.serializedName}")
        return if (DeskModel.ink(inkTier) >= units && DeskModel.paper(chosenPaper) > 0) {
            Component.translatable("container.agesandtheart.writers_desk.write_costs", ink, units)
        } else {
            Component.translatable("container.agesandtheart.writers_desk.write_short", ink, units)
        }
    }

    /** Asks the server for a quote if this word has never been priced, and says so meanwhile. */
    private fun quoteFor(word: Identifier): Component {
        if (DeskModel.startAsking(word)) send(DeskAction.PRICE, word = word)
        return translated("write_unpriced")
    }

    /** Picking a word asks what it costs, and on the tabs where a click means something, does that too. */
    private fun chooseWord(row: WordRow) {
        if (selectedWord != row.word) {
            selectedWord = row.word
            if (DeskModel.startAsking(row.word)) send(DeskAction.PRICE, word = row.word)
        }
        when (tab) {
            // The archive selects: which word the paper buttons write, and nothing more. Taking a page out
            // is its own button now rather than a side effect of looking at a row.
            DeskTab.ARCHIVE -> Unit
            DeskTab.WRITE_BOOK ->
                if (row.inArchive > 0) send(DeskAction.COMPOSE_FROM_ARCHIVE, word = row.word)
            DeskTab.BIND -> Unit
        }
    }

    /** Whether a page could be written on [paper] at all — the ink for it, and a sheet to put it on. */
    private fun canWrite(paper: InkTier): Boolean {
        if (DeskModel.paper(paper) <= 0) return false
        val (inkTier, units) = DeskModel.priceFor(selectedWord, paper) ?: return true
        return DeskModel.ink(inkTier) >= units
    }

    /**
     * The only drawing left, and it is all text over widgets that have already placed themselves.
     *
     * **In `extractContents`, not `extractBackground`.** The background is its own stratum, laid under
     * every widget on the screen — so the panel painted over every word of this, and a refusal the desk
     * had gone to the trouble of sending arrived invisible.
     */
    override fun extractContents(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractContents(graphics, mouseX, mouseY, a)
        if (tab == DeskTab.ARCHIVE) extractPrices(graphics)
        if (tab == DeskTab.WRITE_BOOK) extractCompositionHeader(graphics)
        DeskNotice.extract(graphics, font, layout.panel)
    }

    /**
     * What each paper choice would cost in ink, under its button and red when it is out of reach.
     *
     * Read off the button rather than placed: the column decided where the row went, so asking it is the
     * only way this cannot drift.
     */
    private fun extractPrices(graphics: GuiGraphicsExtractor) {
        val word = selectedWord ?: return
        InkTier.entries.forEachIndexed { index, paper ->
            val price = DeskModel.priceFor(word, paper) ?: return@forEachIndexed
            val button = paperButtons[index]
            val colour = if (DeskModel.ink(price.first) >= price.second) Palette.TEXT else Palette.WARNING
            graphics.text(
                font, inkGlyph(price.first),
                button.x, button.y + button.height + PRICE_DROP, colour, false,
            )
        }
    }

    /**
     * Sits in the line the column left above the work surface for it: how much of the book is spoken for,
     * and — when this desk cannot see them — that an unmarked page proves nothing.
     *
     * Saying so is not giving the currency away (design §7.3). A writer who is shown no quarrels at a bare
     * desk otherwise reads that as *there are none*, which is the one thing the absence does not mean.
     */
    private fun extractCompositionHeader(graphics: GuiGraphicsExtractor) {
        val written = DeskModel.composing().size
        val limit = DeskModel.pageLimit()
        val header = if (limit == null) "$written" else "$written / $limit"
        val baseline = composition.y - LINE
        graphics.text(font, header, composition.x, baseline, Palette.FAINT, false)
        if (DeskModel.can(DeskCapability.REVEAL_CONFLICTS)) return
        val unseen = translated("conflicts_unseen")
        graphics.text(font, unseen, composition.right - font.width(unseen), baseline, Palette.FAINT, false)
    }

    /** Exactly what the tank holds, since a gauge can only ever say roughly. */
    private fun inkTooltip(tier: InkTier): Component {
        val held = DeskModel.ink(tier)
        val perBucket = (DeskModel.inkCapacity() / AgeFluids.TANK_CAPACITY_BUCKETS).coerceAtLeast(1)
        return Component.translatable(
            "container.agesandtheart.writers_desk.ink",
            translated("ink.${tier.key}"),
            String.format("%.2f", held.toDouble() / perBucket),
            AgeFluids.TANK_CAPACITY_BUCKETS,
        )
    }

    private fun send(
        action: DeskAction,
        word: Identifier? = null,
        paper: InkTier = InkTier.COMMON,
        index: Int = -1,
        target: Int = -1,
        title: String = "",
    ) {
        ClientDeskNetwork.send(DeskCommandPayload(action, word, paper, index, target, title))
    }

    private fun translated(suffix: String): Component =
        Component.translatable("container.agesandtheart.writers_desk.$suffix")

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

    private companion object {
        /** What a binding looks like in the stock column. */
        val BINDING_ICON = ItemStack(Items.LEATHER)

        const val LINE = 12
        const val GAP = 4
        const val PAPER_BUTTON_WIDTH = 30
        const val NAME_WIDTH = 60
        const val BIND_WIDTH = 30
        const val PRICE_DROP = 4

        /** Three rows: enough to pick from with the search box doing the finding. */
        const val BOOK_WORD_LIST_HEIGHT = 36

        // The wing's contents. Its padding is asymmetric because the border eats the left edge and not the
        // open right, and because the gauges want more room above them than the stocks want below.
        val WING_PADDING = Insets(left = 7, top = 12, right = 4, bottom = 6)
        const val GAUGE_WIDTH = 9
        const val GAUGE_HEIGHT = 58
        const val GAUGE_GAP = 4
        const val GROUP_GAP = 6
        const val STOCK_WIDTH = 35
        const val STOCK_LINE = 14
    }
}

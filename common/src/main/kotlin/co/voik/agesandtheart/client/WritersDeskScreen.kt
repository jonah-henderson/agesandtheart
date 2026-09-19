package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.age.word.WordNames
import co.voik.agesandtheart.client.ui.DecoratedBox
import co.voik.agesandtheart.client.ui.DecorationWidget
import co.voik.agesandtheart.client.ui.Edge
import co.voik.agesandtheart.client.ui.FlexColumn
import co.voik.agesandtheart.client.ui.Insets
import co.voik.agesandtheart.client.ui.LabelledList
import co.voik.agesandtheart.client.ui.MarkedText
import co.voik.agesandtheart.client.ui.Palette
import co.voik.agesandtheart.client.ui.PanelSurface
import co.voik.agesandtheart.client.ui.PricedItem
import co.voik.agesandtheart.client.ui.Rect
import co.voik.agesandtheart.client.ui.TemplateEditor
import co.voik.agesandtheart.client.ui.TextMark
import co.voik.agesandtheart.desk.BookCost
import co.voik.agesandtheart.desk.DeskBindPayload
import co.voik.agesandtheart.desk.DeskCapability
import co.voik.agesandtheart.desk.DeskTemplatePayload
import co.voik.agesandtheart.desk.ReadWord
import co.voik.agesandtheart.desk.WordState
import co.voik.agesandtheart.desk.WritersDeskMenu
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.FittingMultiLineTextWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/**
 * The writer's desk: every word you know on the left, the template in the middle, the stock on the right
 * (design: the writer's desk redesign).
 *
 * **One screen, no tabs and no inventory.** A template is typed, or built by clicking words in from the
 * list; the desk reads it back — each word underlined as the reader took it, its D'ni beneath where the
 * writer has learned it — prices the book, and binds it with one click.
 *
 * Composes and decides; draws nothing itself but lines of text. Widgets are added **back to front**.
 */
class WritersDeskScreen(
    menu: WritersDeskMenu,
    inventory: Inventory,
    title: Component,
) : AbstractContainerScreen<WritersDeskMenu>(menu, inventory, title, PANEL_WIDTH, PANEL_HEIGHT) {

    private lateinit var search: EditBox
    private lateinit var wordList: LabelledList<WordRow>
    private lateinit var ageName: EditBox
    private lateinit var editor: TemplateEditor
    private lateinit var readingView: FittingMultiLineTextWidget
    private lateinit var costRow: LinearLayout
    private lateinit var costItems: List<PricedItem>
    private lateinit var paperButtons: List<Button>
    private lateinit var readButton: Button
    private lateinit var bindButton: Button

    private var shownWords: List<WordRow> = emptyList()

    /** Whether the editor has been given the template the desk kept, which happens once per opening. */
    private var seeded = false

    /** Ticks since the template last changed and was not yet sent, or null when the desk has it. */
    private var unsentFor: Int? = null

    private var showingReading = false

    /** The template as it was when Bind was pressed, until the desk either clears it or keeps it. */
    private var bindingTemplate: String? = null

    init {
        // Whatever the last desk said is not this desk's.
        DeskModel.forget()
    }

    override fun init() {
        super.init()
        // Vanilla centres the panel alone, but the stock hangs off its right edge.
        leftPos -= STOCK_ALLOWANCE / 2
        inventoryLabelY = OFFSCREEN
        val keptFilter = if (::search.isInitialized) search.value else ""
        val keptTemplate = if (::editor.isInitialized) editor.value else null
        val keptName = if (::ageName.isInitialized) ageName.value else ""

        addRenderableWidget(
            DecorationWidget(PanelSurface.RAISED).also {
                it.setPosition(leftPos, topPos)
                it.setSize(imageWidth, imageHeight)
            },
        )
        addStock()
        addWordColumn(keptFilter)
        addTemplateColumn(keptTemplate, keptName)
    }

    private fun addStock() {
        val contents = LinearLayout.vertical().spacing(GROUP_GAP)
        contents.addChild(DeskStockDisplay.inkGauges(GAUGE_WIDTH, GAUGE_HEIGHT, GAUGE_GAP))
        contents.addChild(DeskStockDisplay.stockColumn(STOCK_WIDTH, STOCK_LINE))
        val stock = DecoratedBox(PanelSurface(openOn = Edge.LEFT), STOCK_PADDING)
        stock.holding(contents)
        stock.arrangeElements()
        // Overlapping the panel's border, so the two borders meet instead of stopping short of each other.
        stock.sized(stock.width + Palette.BORDER, stock.height)
        stock.setPosition(leftPos + imageWidth - Palette.BORDER, topPos)
        stock.arrangeElements()
        stock.visitWidgets(::addRenderableWidget)
    }

    private fun addWordColumn(keptFilter: String) {
        search = EditBox(font, 0, 0, 0, LINE, Component.empty())
        search.setHint(translated("search"))
        search.value = keptFilter
        search.setResponder {
            wordList.resetScroll()
            refreshWords(force = true)
        }
        addRenderableWidget(search)
        wordList = addRenderableWidget(LabelledList(Minecraft.getInstance(), Rect(0, 0, 0, 0), ::insertWord))

        val column = FlexColumn(WORD_COLUMN_WIDTH, imageHeight - CONTENT_TOP - INSET)
        column.add(search, height = LINE)
        column.gap(GAP)
        column.fill(wordList)
        column.setPosition(leftPos + INSET, topPos + CONTENT_TOP)
        column.arrangeElements()
        refreshWords(force = true)
    }

    private fun addTemplateColumn(keptTemplate: String?, keptName: String) {
        val columnWidth = imageWidth - TEMPLATE_COLUMN_X - INSET

        ageName = EditBox(font, 0, 0, 0, LINE, Component.empty())
        ageName.setHint(translated("name"))
        ageName.setMaxLength(DeskBindPayload.MAX_TITLE)
        ageName.value = keptName
        addRenderableWidget(ageName)

        editor = TemplateEditor(font, columnWidth, 0, translated("template_hint"), ::marks)
        editor.setCharacterLimit(DeskTemplatePayload.MAX_TEMPLATE)
        keptTemplate?.let { editor.value = it }
        editor.onChange { unsentFor = 0 }
        addRenderableWidget(editor)

        readingView = FittingMultiLineTextWidget(0, 0, columnWidth, 0, Component.empty(), font)
        addRenderableWidget(readingView)

        costRow = LinearLayout.horizontal().spacing(COST_GAP)
        costItems = listOf(
            *InkTier.entries.map(::inkPrice).toTypedArray(),
            PricedItem(
                PAPER_PRICE_WIDTH,
                icon = { DeskStockDisplay.paperIcon(chosenPaper) },
                amount = { "${currentCost().sheets}" },
                isShort = { DeskModel.paper(chosenPaper) < currentCost().sheets },
                tooltip = {
                    val price = priceTooltip(
                        DeskStockDisplay.paperName(chosenPaper),
                        "${currentCost().sheets}",
                        "${DeskModel.paper(chosenPaper)}",
                    )
                    val drawn = DeskModel.drawn()
                    if (drawn == 0) listOf(price) else listOf(price, faint(translated("pages_drawn", drawn)))
                },
            ),
            PricedItem(
                BINDING_PRICE_WIDTH,
                icon = { DeskStockDisplay.BINDING },
                amount = { "${currentCost().bindings}" },
                isShort = { DeskModel.binding() < currentCost().bindings },
                tooltip = {
                    listOf(priceTooltip(DeskStockDisplay.BINDING.hoverName, "${currentCost().bindings}", "${DeskModel.binding()}"))
                },
            ),
        )
        costItems.forEach { costRow.addChild(it); addRenderableWidget(it) }

        val controls = LinearLayout.horizontal().spacing(GAP)
        paperButtons = InkTier.entries.map { paper ->
            Button.builder(Component.literal(paperGlyph(paper))) { chosenPaper = paper }
                .bounds(0, 0, TOGGLE_WIDTH, BUTTON_HEIGHT)
                .tooltip(Tooltip.create(DeskStockDisplay.paperName(paper)))
                .build()
                .also(controls::addChild)
        }
        readButton = Button.builder(translated("preview")) { showingReading = !showingReading }
            .bounds(0, 0, READ_BUTTON_WIDTH, BUTTON_HEIGHT).build()
            .also(controls::addChild)
        bindButton = Button.builder(translated("bind")) { bind() }
            .bounds(0, 0, BIND_BUTTON_WIDTH, BUTTON_HEIGHT).build()
        (paperButtons + readButton + bindButton).forEach(::addRenderableWidget)

        val column = FlexColumn(columnWidth, imageHeight - CONTENT_TOP - INSET)
        column.add(ageName, height = LINE)
        column.gap(GAP)
        column.fill(editor)
        column.gap(GAP)
        column.add(costRow)
        column.gap(GAP)
        column.add(controls)
        column.setPosition(leftPos + TEMPLATE_COLUMN_X, topPos + CONTENT_TOP)
        column.arrangeElements()

        readingView.setPosition(editor.x, editor.y)
        readingView.setWidth(editor.width)
        readingView.height = editor.height
        // The bind button stands at the right of the row, apart from the choices it acts on.
        bindButton.setPosition(leftPos + imageWidth - INSET - BIND_BUTTON_WIDTH, controls.y)
        focused = editor
    }

    /** A word from the list goes in at the cursor, as the name the list shows it by. */
    private fun insertWord(row: WordRow) {
        editor.insertWord(row.readable)
        focused = editor
    }

    private fun bind() {
        sendTemplateNow()
        bindingTemplate = editor.value
        sendToServer(DeskBindPayload(ageName.value, chosenPaper))
    }

    /**
     * A bound book leaves the desk's template empty, so the editor and the name follow it. A refused bind
     * leaves the template as it was, and anything typed since the click is the writer's and stays.
     */
    private fun clearOnceBound() {
        val bound = bindingTemplate ?: return
        if (editor.value != bound) {
            bindingTemplate = null
            return
        }
        if (DeskModel.template() != "") return
        bindingTemplate = null
        editor.value = ""
        ageName.value = ""
        unsentFor = null
    }

    /**
     * A key pressed while a box has the caret belongs to the box, whether or not it wanted it — or `e`
     * reaches "close the inventory" and the desk shuts mid-word. Vanilla's creative search is guarded so.
     */
    override fun keyPressed(event: KeyEvent): Boolean {
        val typingInto = listOf(search, ageName, editor).firstOrNull { it.visible && it.isFocused }
            ?: return super.keyPressed(event)
        if (typingInto.keyPressed(event)) return true
        return if (event.isEscape) super.keyPressed(event) else true
    }

    override fun containerTick() {
        super.containerTick()
        seedFromTheDesk()
        clearOnceBound()
        unsentFor?.let { waited ->
            if (waited >= SEND_AFTER_TICKS) sendTemplateNow() else unsentFor = waited + 1
        }
        refreshWords(force = false)
        refreshControls()
    }

    /** Sends a template still waiting out its pause before the screen closes, so nothing typed is lost. */
    override fun removed() {
        sendTemplateNow()
        super.removed()
    }

    private fun seedFromTheDesk() {
        if (seeded) return
        val kept = DeskModel.template() ?: return
        if (editor.value.isEmpty()) editor.value = kept
        seeded = true
        unsentFor = null
    }

    private fun sendTemplateNow() {
        if (unsentFor == null) return
        unsentFor = null
        sendToServer(DeskTemplatePayload(editor.value))
    }

    private fun refreshWords(force: Boolean) {
        val rows = DeskModel.knownRows(search.value)
        if (!force && rows == shownWords) return
        shownWords = rows
        wordList.show(rows, label = { it.readable }, key = { it.word })
    }

    private fun refreshControls() {
        paperButtons.forEachIndexed { index, button ->
            val paper = InkTier.entries[index]
            // Dark for the chosen one, so the row of three reads as a setting rather than three actions.
            button.active = paper != chosenPaper
        }
        val readable = DeskModel.can(DeskCapability.READABLE_GRAMMAR)
        readButton.visible = readable
        if (!readable) showingReading = false
        readButton.message = translated(if (showingReading) "write_again" else "preview")
        // A refusal takes the cost row's place for as long as it is shown.
        val refused = DeskNotice.current() != null
        costItems.forEach { it.visible = !refused }
        editor.visible = !showingReading
        readingView.visible = showingReading
        readingView.message = readingText()
    }

    /** The runs of the template as the desk last read them, with the text they were read from. */
    private fun marks(): MarkedText? {
        val readFrom = DeskModel.template() ?: return null
        return MarkedText(readFrom, DeskModel.read().map(::markFor))
    }

    private fun markFor(read: ReadWord): TextMark = when (read.state) {
        WordState.LEARNED -> {
            val word = read.word
            val quarrel = DeskModel.quarrels().firstOrNull { it.word == word }
            TextMark(
                read.start, read.end,
                script = word?.let(KnownWords::scriptText),
                colour = quarrel?.let { Palette.WARNING },
                underline = if (quarrel != null) Palette.WARNING else UNDERLINE,
                tooltip = quarrel?.let {
                    if (it.word == it.against) translated("quarrel_alone")
                    else translated("quarrel", WordNames.readable(it.against))
                },
            )
        }
        WordState.UNLEARNED -> TextMark(
            read.start, read.end,
            scriptUnknown = true, underline = UNDERLINE, tooltip = translated("translation_unknown"),
        )
        WordState.UNKNOWN -> TextMark(
            read.start, read.end,
            colour = Palette.WARNING, underline = Palette.WARNING, tooltip = translated("not_a_word"),
        )
    }

    /**
     * **An empty reading means two different things and has to say which.** Nothing written is a writer
     * with an empty template; nothing *readable* is a desk that cannot read (design §7.3).
     */
    private fun readingText(): Component {
        val said = DeskModel.reading()
        return if (said.isEmpty()) translated("nothing_written") else Component.literal(said)
    }

    /** What binding the template would cost now, on the chosen paper. */
    private fun currentCost(): BookCost = BookCost.of(DeskModel.toWrite(), chosenPaper)

    private fun inkPrice(tier: InkTier): PricedItem {
        fun needed() = currentCost().ink[tier] ?: 0L
        fun savedByPaper() = (BookCost.of(DeskModel.toWrite(), InkTier.COMMON).ink[tier] ?: 0L) - needed()
        return PricedItem(
            INK_PRICE_WIDTH,
            icon = { DeskStockDisplay.inkIcon(tier) },
            amount = { DeskModel.inBottles(needed()) },
            isShort = { DeskModel.ink(tier) < needed() },
            tooltip = {
                val price = translated(
                    "ink_price",
                    DeskStockDisplay.inkName(tier),
                    DeskModel.inBottles(needed()),
                    DeskModel.inBottles(DeskModel.ink(tier), roundUp = false),
                )
                val saved = savedByPaper()
                if (saved <= 0L) {
                    listOf(price)
                } else {
                    val savedLine = translated("ink_saved", DeskModel.inBottles(saved), DeskStockDisplay.paperName(chosenPaper))
                    listOf(price, faint(savedLine))
                }
            },
        )
    }

    private fun priceTooltip(name: Component, needed: String, held: String): Component =
        translated("price", name, needed, held)

    private fun faint(line: Component): Component = line.copy().withStyle(ChatFormatting.GRAY)

    /**
     * Beside the cost row, how long the book is against the page limit — or, in the row's place, whatever
     * the desk last refused with.
     */
    override fun extractContents(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractContents(graphics, mouseX, mouseY, a)
        val baseline = costRow.y + (Palette.ITEM - font.lineHeight) / 2 + 1
        val notice = DeskNotice.current()
        if (notice != null) {
            graphics.text(font, notice, editor.x, baseline, Palette.WARNING, false)
            return
        }
        val words = DeskModel.read().size
        val limit = DeskModel.pageLimit()
        val length = if (limit == null) "$words" else "$words/$limit"
        val tooLong = limit != null && words > limit
        val right = editor.x + editor.width
        graphics.text(font, length, right - font.width(length), baseline, if (tooLong) Palette.WARNING else Palette.FAINT, false)
    }

    private fun translated(suffix: String, vararg arguments: Any): Component =
        Component.translatable("container.agesandtheart.writers_desk.$suffix", *arguments)

    private fun paperGlyph(tier: InkTier): String = when (tier) {
        InkTier.COMMON -> "I"
        InkTier.FINE -> "II"
        InkTier.MASTERWORK -> "III"
    }

    private companion object {
        /** The paper, kept between openings on this client. The cheapest by default, so nobody wastes the good stuff. */
        var chosenPaper = InkTier.COMMON

        const val PANEL_WIDTH = 320
        const val PANEL_HEIGHT = 200

        /** Roughly what the stock hanging off the right edge takes, so the whole is centred rather than the panel. */
        const val STOCK_ALLOWANCE = 70

        const val INSET = 8
        const val CONTENT_TOP = 18
        const val WORD_COLUMN_WIDTH = 100
        const val TEMPLATE_COLUMN_X = INSET + WORD_COLUMN_WIDTH + INSET
        const val LINE = 12
        const val GAP = 4
        const val BUTTON_HEIGHT = 14
        const val TOGGLE_WIDTH = 20
        const val READ_BUTTON_WIDTH = 50

        /** Each wide enough for its icon and the longest amount it is likely to say — `12.5`, `40`, `1`. */
        const val INK_PRICE_WIDTH = 34
        const val PAPER_PRICE_WIDTH = 28
        const val BINDING_PRICE_WIDTH = 24
        const val COST_GAP = 2
        const val BIND_BUTTON_WIDTH = 44

        /** How long a pause in typing is before the desk is asked to read it — a quarter of a second. */
        const val SEND_AFTER_TICKS = 5

        /** Far enough down that vanilla's inventory label is simply not drawn. There is no inventory. */
        const val OFFSCREEN = 10_000

        val UNDERLINE = 0x806B5C46.toInt()

        val STOCK_PADDING = Insets(left = 4, top = 12, right = 7, bottom = 6)
        const val GAUGE_WIDTH = 9
        const val GAUGE_HEIGHT = 58
        const val GAUGE_GAP = 4
        const val GROUP_GAP = 6
        const val STOCK_WIDTH = 35
        const val STOCK_LINE = 14
    }
}

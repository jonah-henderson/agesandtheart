package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.word.WordNames
import co.voik.agesandtheart.client.ui.DecorationWidget
import co.voik.agesandtheart.client.ui.FlexColumn
import co.voik.agesandtheart.client.ui.LabelledList
import co.voik.agesandtheart.client.ui.Palette
import co.voik.agesandtheart.client.ui.PanelSurface
import co.voik.agesandtheart.client.ui.PlayerInventoryView
import co.voik.agesandtheart.client.ui.Rect
import co.voik.agesandtheart.client.ui.RowAction
import co.voik.agesandtheart.desk.ArchiveMenu
import co.voik.agesandtheart.desk.ArchiveSyncPayload
import co.voik.agesandtheart.desk.ArchiveWithdrawPayload
import co.voik.agesandtheart.desk.PageArchive
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Inventory

/** One word an archive holds, and how many pages of it. */
data class ArchiveRow(val word: Identifier, val readable: String, val count: Int)

/**
 * An archive's pages, searched, above the player's inventory.
 *
 * Shift-clicking a page or a notebook in the inventory files it; a row's button hands pages back — one,
 * or a stackful with shift held.
 */
class ArchiveScreen(menu: ArchiveMenu, inventory: Inventory, title: Component) :
    AbstractContainerScreen<ArchiveMenu>(menu, inventory, title, ArchiveMenu.PANEL_WIDTH, ArchiveMenu.PANEL_HEIGHT) {

    private lateinit var search: EditBox
    private lateinit var list: LabelledList<ArchiveRow>
    private var shown: List<ArchiveRow> = emptyList()

    override fun init() {
        super.init()
        inventoryLabelY = ArchiveMenu.INVENTORY_LABEL_Y
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
        addRenderableWidget(search)
        list = addRenderableWidget(LabelledList(Minecraft.getInstance(), Rect(0, 0, 0, 0)) {})

        val column = FlexColumn(imageWidth - INSET * 2, ArchiveMenu.INVENTORY_LABEL_Y - GAP - CONTENT_TOP)
        column.add(search, height = LINE)
        column.gap(GAP)
        column.fill(list)
        column.setPosition(leftPos + INSET, topPos + CONTENT_TOP)
        column.arrangeElements()

        focused = search
        refresh(force = true)
    }

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
    }

    override fun containerTick() {
        super.containerTick()
        refresh(force = false)
    }

    /** An empty archive says what it is for, where its pages would be. */
    override fun extractContents(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractContents(graphics, mouseX, mouseY, a)
        if (held.words.isNotEmpty()) return
        graphics.textWithWordWrap(font, translated("hint"), list.x + HINT_INSET, list.y + HINT_INSET, list.width - 2 * HINT_INSET, Palette.FAINT)
    }

    private fun refresh(force: Boolean) {
        val needle = search.value.trim().lowercase()
        val rows = held.words
            .map { ArchiveRow(it, WordNames.readable(it).string, held.count(it)) }
            .filter { needle.isEmpty() || it.readable.lowercase().contains(needle) }
            .sortedBy { it.readable }
        if (!force && rows == shown) return
        shown = rows
        list.show(rows, label = { it.readable }, count = { it.count }, key = { it.word }, actions = listOf(takeOut))
    }

    private val takeOut = RowAction<ArchiveRow>(
        glyph = "▼",
        tooltip = { translated("withdraw") },
        act = { row ->
            sendToServer(ArchiveWithdrawPayload(row.word, wholeStack = Minecraft.getInstance().hasShiftDown()))
        },
    )

    private fun translated(suffix: String): Component =
        Component.translatable("container.agesandtheart.archive.$suffix")

    companion object {
        /** What the open archive holds, as last sent. One archive screen is open at a time. */
        private var held: PageArchive = PageArchive.EMPTY

        fun remember(payload: ArchiveSyncPayload) {
            held = payload.pages
        }

        private const val LINE = 12
        private const val GAP = 4
        private const val INSET = 8
        private const val CONTENT_TOP = 18
        private const val HINT_INSET = 2
    }
}

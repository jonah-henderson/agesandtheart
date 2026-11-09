package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.BookBanner
import co.voik.agesandtheart.book.BookbinderMenu
import co.voik.agesandtheart.book.BookbinderRenamePayload
import co.voik.agesandtheart.book.BookbinderSlots
import co.voik.agesandtheart.book.CoverLayout
import co.voik.agesandtheart.client.ui.DecorationWidget
import co.voik.agesandtheart.client.ui.PanelSurface
import co.voik.agesandtheart.client.ui.PlayerInventoryView
import co.voik.agesandtheart.client.ui.WellSurface
import co.voik.agesandtheart.client.ui.SlotView
import co.voik.agesandtheart.content.AgeComponents
import co.voik.agesandtheart.desk.DeskSlots
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/**
 * The bookbinder's screen: the book's name across the top with the ink for it to its right; under them the book and
 * the banner it wears, the three ways of laying a cover out stacked, the one it is laid out in shown pressed, and the
 * book as the ink would leave it, to take. Nothing but the ink is confirmed: every other change goes to the book at
 * once, so shutting the screen shows it on the press.
 */
class BookbinderScreen(menu: BookbinderMenu, inventory: Inventory, title: Component) :
    AbstractContainerScreen<BookbinderMenu>(menu, inventory, title, DeskSlots.PANEL_WIDTH, BookbinderSlots.PANEL_HEIGHT) {


    private lateinit var name: EditBox
    private val layoutButtons = mutableListOf<Button>()

    /** Whether the screen last saw a book in the press, so the name is filled in once as one arrives. */
    private var sawABook = false

    override fun init() {
        super.init()
        inventoryLabelY = BookbinderSlots.INVENTORY_LABEL_Y
        addRenderableWidget(
            DecorationWidget(PanelSurface.RAISED).also {
                it.setPosition(leftPos, topPos)
                it.setSize(imageWidth, imageHeight)
            },
        )
        name = EditBox(
            font,
            leftPos + BookbinderSlots.NAME_X,
            topPos + BookbinderSlots.NAME_Y,
            BookbinderSlots.NAME_WIDTH,
            BookbinderSlots.NAME_HEIGHT,
            translated("name"),
        )
        name.setMaxLength(BookbinderMenu.MAX_NAME_LENGTH)
        name.setResponder { typed ->
            val isANewName = typed != menu.book.get(DataComponents.CUSTOM_NAME)?.string.orEmpty()
            if (!menu.book.isEmpty && isANewName) sendToServer(BookbinderRenamePayload(typed))
        }
        addRenderableWidget(name)
        addRenderableWidget(SlotView(leftPos + BookbinderSlots.INK_X, topPos + BookbinderSlots.INK_Y))
        addRenderableWidget(SlotView(leftPos + BookbinderSlots.BOOK_X, topPos + BookbinderSlots.SLOTS_Y))
        addRenderableWidget(SlotView(leftPos + BookbinderSlots.COVER_X, topPos + BookbinderSlots.SLOTS_Y))
        addRenderableWidget(
            DecorationWidget(WellSurface).also {
                it.setPosition(leftPos + BookbinderSlots.OUTPUT_FRAME_X, topPos + BookbinderSlots.OUTPUT_FRAME_Y)
                it.setSize(BookbinderSlots.OUTPUT_SIZE, BookbinderSlots.OUTPUT_SIZE)
            },
        )
        layoutButtons.clear()
        for ((index, layout) in CoverLayout.entries.withIndex()) {
            val y = topPos + BookbinderSlots.LAYOUTS_Y + index * BookbinderSlots.LAYOUT_PITCH
            val button = Button.builder(translated("layout.${layout.serializedName}")) { press(index) }
                .bounds(leftPos + BookbinderSlots.LAYOUTS_X, y, BookbinderSlots.LAYOUT_WIDTH, BookbinderSlots.LAYOUT_HEIGHT)
                .tooltip(Tooltip.create(translated("layout.${layout.serializedName}.description")))
                .build()
            layoutButtons += addRenderableWidget(button)
        }
        addRenderableWidget(
            PlayerInventoryView(leftPos + DeskSlots.INVENTORY_X, topPos + BookbinderSlots.INVENTORY_Y, DeskSlots.HOTBAR_DROP),
        )
        sawABook = false
        follow()
    }

    override fun containerTick() {
        super.containerTick()
        follow()
    }

    /** The name and buttons as the book in the press now stands. */
    private fun follow() {
        val book = menu.book
        val hasABook = !book.isEmpty
        if (hasABook != sawABook) {
            sawABook = hasABook
            name.value = book.get(DataComponents.CUSTOM_NAME)?.string.orEmpty()
            name.setHint(if (hasABook) book.hoverName.copy() else translated("name.no_book"))
        }
        name.setEditable(hasABook)
        val isCovered = hasABook && BookBanner.isCovered(book)
        val laidOut = book.getOrDefault(AgeComponents.COVER_LAYOUT, CoverLayout.DEFAULT)
        for ((index, button) in layoutButtons.withIndex()) {
            button.active = isCovered && CoverLayout.entries[index] != laidOut
        }
    }

    private fun press(index: Int) {
        Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, index)
    }

    /** A key typed into the name goes to it; only escape still shuts the screen. */
    override fun keyPressed(event: KeyEvent): Boolean {
        if (!name.isFocused) return super.keyPressed(event)
        if (name.keyPressed(event)) return true
        return if (event.isEscape) super.keyPressed(event) else true
    }

    private companion object {

        fun translated(key: String): Component = Component.translatable("container.agesandtheart.bookbinder.$key")
    }
}

package co.voik.agesandtheart.client

import co.voik.agesandtheart.client.ui.DecorationWidget
import co.voik.agesandtheart.client.ui.PanelSurface
import co.voik.agesandtheart.client.ui.PlayerInventoryView
import co.voik.agesandtheart.client.ui.ProgressArrow
import co.voik.agesandtheart.client.ui.SlotView
import co.voik.agesandtheart.desk.DeskSlots
import co.voik.agesandtheart.station.CompounderBlockEntity
import co.voik.agesandtheart.station.CompounderMenu
import co.voik.agesandtheart.station.CompounderNeed
import co.voik.agesandtheart.station.CompounderSlots
import net.minecraft.client.gui.components.ImageButton
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/**
 * The compounder's screen. The arrow is full while there is a result to take, since compounding is
 * instant; under the inputs, what their recipe still wants beside the machine.
 */
class CompounderScreen(menu: CompounderMenu, inventory: Inventory, title: Component) :
    AbstractContainerScreen<CompounderMenu>(menu, inventory, title, DeskSlots.PANEL_WIDTH, DeskSlots.WING_PANEL_HEIGHT) {

    private lateinit var status: StringWidget
    private lateinit var book: CompoundingBook

    override fun init() {
        super.init()
        inventoryLabelY = DeskSlots.WING_INVENTORY_LABEL_Y

        addRenderableWidget(
            DecorationWidget(PanelSurface.RAISED).also {
                it.setPosition(leftPos, topPos)
                it.setSize(DeskSlots.PANEL_WIDTH, DeskSlots.WING_PANEL_HEIGHT)
            },
        )
        for (slot in 0..<CompounderBlockEntity.INPUT_SLOTS) {
            val column = slot % CompounderSlots.INPUT_COLUMNS
            val row = slot / CompounderSlots.INPUT_COLUMNS
            addRenderableWidget(
                SlotView(leftPos + CompounderSlots.INPUT_X + column * SLOT_PITCH, topPos + CompounderSlots.INPUT_Y + row * SLOT_PITCH),
            )
        }
        addRenderableWidget(SlotView(leftPos + CompounderSlots.RESULT_X, topPos + CompounderSlots.RESULT_Y))
        addRenderableWidget(
            ProgressArrow(leftPos + CompounderSlots.ARROW_X, topPos + CompounderSlots.ARROW_Y) {
                if (menu.hasAResult) 1f else 0f
            },
        )
        status = StringWidget(
            leftPos + DeskSlots.INVENTORY_X,
            topPos + CompounderSlots.STATUS_Y,
            DeskSlots.PANEL_WIDTH - DeskSlots.INVENTORY_X * 2,
            font.lineHeight,
            Component.empty(),
            font,
        )
        addRenderableWidget(status)
        addRenderableWidget(
            PlayerInventoryView(leftPos + DeskSlots.INVENTORY_X, topPos + DeskSlots.WING_INVENTORY_Y, DeskSlots.HOTBAR_DROP),
        )
        // Beside the panel rather than shifting it, so opening the book moves nothing already on screen.
        book = CompoundingBook((leftPos - CompoundingBook.WIDTH - BOOK_GAP).coerceAtLeast(0), topPos)
        book.visible = CompoundingBook.isOpen
        addRenderableWidget(book)
        addRenderableWidget(
            ImageButton(
                leftPos + BOOK_BUTTON_X,
                topPos + BOOK_BUTTON_Y,
                BOOK_BUTTON_WIDTH,
                BOOK_BUTTON_HEIGHT,
                RecipeBookComponent.RECIPE_BUTTON_SPRITES,
            ) {
                CompoundingBook.isOpen = !CompoundingBook.isOpen
                book.visible = CompoundingBook.isOpen
            },
        )
    }

    /** A click on the open book is not a click outside, which would throw down whatever is carried. */
    override fun hasClickedOutside(mx: Double, my: Double, xo: Int, yo: Int): Boolean =
        super.hasClickedOutside(mx, my, xo, yo) && !(book.visible && book.isMouseOver(mx, my))

    override fun containerTick() {
        super.containerTick()
        status.message = statusFor(menu.missingNeeds)
    }

    private fun statusFor(missing: List<CompounderNeed>): Component =
        if (missing.isEmpty()) Component.empty()
        else Component.translatable(
            "container.agesandtheart.fusion_compounder.needs",
            missing.map { Component.translatable("container.agesandtheart.fusion_compounder.need.${it.serializedName}") }
                .reduce { listed, next -> Component.translatable("container.agesandtheart.fusion_compounder.and", listed, next) },
        )

    private companion object {
        const val SLOT_PITCH = 18
        const val BOOK_GAP = 2

        /** Vanilla's green book, at the left of the inputs where a furnace keeps it. */
        const val BOOK_BUTTON_X = 10
        const val BOOK_BUTTON_Y = 26
        const val BOOK_BUTTON_WIDTH = 20
        const val BOOK_BUTTON_HEIGHT = 18
    }
}

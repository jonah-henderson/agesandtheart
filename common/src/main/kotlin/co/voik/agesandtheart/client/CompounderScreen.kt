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
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.navigation.ScreenPosition
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/**
 * The compounder's screen, with vanilla's recipe book beside it. The arrow is full while there is a result
 * to take, since compounding is instant; under the inputs, what their recipe still wants beside the machine.
 *
 * **The panel is drawn as vanilla draws a furnace's texture**, from [extractBackground], so the book and
 * its button land on top of it. Its pieces are still `client/ui` widgets, held apart from the screen's own
 * and moved with it when the book opens and shifts the screen across.
 *
 * Its size is vanilla's default, 176 by 166, which a recipe-book screen cannot change and the panel is.
 */
class CompounderScreen(menu: CompounderMenu, inventory: Inventory, title: Component) :
    AbstractRecipeBookScreen<CompounderMenu>(menu, CompoundingBook(menu), inventory, title) {

    /** A piece of the panel, and where it sits on it. */
    private class Placed(val widget: AbstractWidget, val x: Int, val y: Int)

    private val panel = mutableListOf<Placed>()

    private lateinit var status: StringWidget

    override fun init() {
        super.init()
        inventoryLabelY = DeskSlots.WING_INVENTORY_LABEL_Y
        panel.clear()
        place(DecorationWidget(PanelSurface.RAISED).also { it.setSize(imageWidth, imageHeight) }, 0, 0)
        for (slot in 0..<CompounderBlockEntity.INPUT_SLOTS) {
            val column = slot % CompounderSlots.INPUT_COLUMNS
            val row = slot / CompounderSlots.INPUT_COLUMNS
            val slotX = CompounderSlots.INPUT_X + column * SLOT_PITCH
            val slotY = CompounderSlots.INPUT_Y + row * SLOT_PITCH
            placeFraming(SlotView(0, 0), slotX, slotY)
        }
        placeFraming(SlotView(0, 0), CompounderSlots.RESULT_X, CompounderSlots.RESULT_Y)
        place(ProgressArrow(0, 0) { if (menu.hasAResult) 1f else 0f }, CompounderSlots.ARROW_X, CompounderSlots.ARROW_Y)
        val statusWidth = imageWidth - DeskSlots.INVENTORY_X * 2
        status = StringWidget(0, 0, statusWidth, font.lineHeight, Component.empty(), font)
        place(status, DeskSlots.INVENTORY_X, CompounderSlots.STATUS_Y)
        placeFraming(PlayerInventoryView(0, 0, DeskSlots.HOTBAR_DROP), DeskSlots.INVENTORY_X, DeskSlots.WING_INVENTORY_Y)
        laidOut()
    }

    /**
     * A recess placed by the item it frames, built at an item at the origin: its own corner sits a little
     * up and to the left of that, and keeps the same offset here.
     */
    private fun placeFraming(widget: AbstractWidget, itemX: Int, itemY: Int) = place(widget, itemX + widget.x, itemY + widget.y)

    private fun place(widget: AbstractWidget, x: Int, y: Int) {
        panel += Placed(widget, x, y)
    }

    private fun laidOut() = panel.forEach { it.widget.setPosition(leftPos + it.x, topPos + it.y) }

    /** Vanilla's green book, at the left of the inputs where a furnace keeps it. */
    override fun getRecipeBookButtonPosition(): ScreenPosition =
        ScreenPosition(leftPos + BOOK_BUTTON_X, topPos + BOOK_BUTTON_Y)

    override fun onRecipeBookButtonClick() = laidOut()

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractBackground(graphics, mouseX, mouseY, a)
        panel.forEach { it.widget.extractRenderState(graphics, mouseX, mouseY, a) }
    }

    /** The compounder's result slot is an ordinary one, unlike a furnace's. */
    override fun isBiggerResultSlot(): Boolean = false

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

        const val BOOK_BUTTON_X = 10
        const val BOOK_BUTTON_Y = 26
    }
}

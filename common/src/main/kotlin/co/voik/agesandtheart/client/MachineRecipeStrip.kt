package co.voik.agesandtheart.client

import co.voik.agesandtheart.client.ui.PanelSurface
import co.voik.agesandtheart.client.ui.Palette
import co.voik.agesandtheart.client.ui.Rect
import co.voik.agesandtheart.client.ui.SlotSurface
import co.voik.agesandtheart.desk.DeskSlots
import co.voik.agesandtheart.station.ListedRecipe
import co.voik.agesandtheart.station.ListsItsRecipes
import co.voik.agesandtheart.station.MachineFillPayload
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.util.Util
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.display.SlotDisplay
import net.minecraft.world.item.crafting.display.SlotDisplayContext

/**
 * A machine's screen with its recipe list down the left, where vanilla's recipe book opens; the machine's
 * own panel moves right to make room.
 */
abstract class RecipeListingScreen<M>(menu: M, inventory: Inventory, title: Component) :
    AbstractContainerScreen<M>(menu, inventory, title, DeskSlots.PANEL_WIDTH, DeskSlots.WING_PANEL_HEIGHT)
    where M : AbstractContainerMenu, M : ListsItsRecipes {

    private fun stripArea(): Rect =
        Rect(leftPos - MachineRecipeStrip.GAP - MachineRecipeStrip.WIDTH, topPos, MachineRecipeStrip.WIDTH, imageHeight)

    /** Adds the strip first, so it sits behind nothing; a subclass lays out its panel after this. */
    override fun init() {
        super.init()
        leftPos += (MachineRecipeStrip.WIDTH + MachineRecipeStrip.GAP) / 2
        addRenderableWidget(MachineRecipeStrip(stripArea(), menu))
    }

    /** A click in the strip's margins is not a click outside the screen, which would throw what is carried. */
    override fun hasClickedOutside(mouseX: Double, mouseY: Double, left: Int, top: Int): Boolean =
        super.hasClickedOutside(mouseX, mouseY, left, top) && !stripArea().contains(mouseX, mouseY)

    /** The container screen never offers a scroll to its widgets, so the strip is asked here. */
    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val strip = children().filterIsInstance<MachineRecipeStrip<*>>().firstOrNull()
        val stripTookIt = strip != null && strip.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        return stripTookIt || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }
}

/**
 * Every recipe the open machine takes, a line each: its input, an arrow, its result. A line whose input
 * the player is not carrying is washed out; clicking one they are moves it into the machine.
 */
class MachineRecipeStrip<M>(area: Rect, private val menu: M) :
    AbstractWidget(area.x, area.y, area.width, area.height, CommonComponents.EMPTY)
    where M : AbstractContainerMenu, M : ListsItsRecipes {

    private var firstShown = 0

    private val rowsThatFit: Int get() = (height - INSET * 2) / ROW_PITCH

    private fun rowArea(row: Int): Rect = Rect(x + INSET, y + INSET + row * ROW_PITCH, ROW_WIDTH, Palette.SLOT)

    private fun inputArea(row: Int): Rect = rowArea(row).let { Rect(it.x, it.y, Palette.SLOT, Palette.SLOT) }

    private fun resultArea(row: Int): Rect = rowArea(row).let { Rect(it.right - Palette.SLOT, it.y, Palette.SLOT, Palette.SLOT) }

    private fun shownRows(): IntRange = 0..<minOf(rowsThatFit, menu.listed.size - firstShown)

    private fun rowAt(mouseX: Double, mouseY: Double): Int? = shownRows().firstOrNull { rowArea(it).contains(mouseX, mouseY) }

    private fun stacksOf(display: SlotDisplay): List<ItemStack> {
        val level = Minecraft.getInstance().level ?: return emptyList()
        return display.resolveForStacks(SlotDisplayContext.fromLevel(level))
    }

    /** One stack of several, turning over each second as vanilla's ghost recipes do. */
    private fun shownStackOf(display: SlotDisplay): ItemStack {
        val stacks = stacksOf(display)
        if (stacks.isEmpty()) return ItemStack.EMPTY
        return stacks[((Util.getMillis() / MILLIS_PER_TURN) % stacks.size).toInt()]
    }

    private fun isCarryingTheInput(recipe: ListedRecipe): Boolean {
        val inputs = stacksOf(recipe.input)
        fun takes(stack: ItemStack) = inputs.any { ItemStack.isSameItem(it, stack) }
        return menu.slots.any { it.container is Inventory && takes(it.item) }
    }

    private fun clickableRowAt(mouseX: Double, mouseY: Double): Int? {
        val row = rowAt(mouseX, mouseY) ?: return null
        return row.takeIf { isCarryingTheInput(menu.listed[firstShown + it]) }
    }

    override fun extractWidgetRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        PanelSurface.RAISED.draw(graphics, Rect(x, y, width, height))
        val font = Minecraft.getInstance().font
        val hoveredRow = clickableRowAt(mouseX.toDouble(), mouseY.toDouble())
        for (row in shownRows()) {
            val recipe = menu.listed[firstShown + row]
            val input = shownStackOf(recipe.input)
            val result = shownStackOf(recipe.result)
            val line = rowArea(row)
            if (row == hoveredRow) graphics.fill(line.x, line.y, line.right, line.bottom, Palette.HOVER)
            drawSlotWithItem(graphics, inputArea(row), input)
            drawSlotWithItem(graphics, resultArea(row), result)
            val arrowX = line.x + (line.width - font.width(ARROW)) / 2
            graphics.text(font, ARROW, arrowX, line.y + (line.height - font.lineHeight) / 2 + 1, Palette.TEXT, false)
            if (!isCarryingTheInput(recipe)) graphics.fill(line.x, line.y, line.right, line.bottom, WASHED_OUT)
            val isOverTheInput = inputArea(row).contains(mouseX.toDouble(), mouseY.toDouble())
            val isOverTheResult = resultArea(row).contains(mouseX.toDouble(), mouseY.toDouble())
            if (isOverTheInput && !input.isEmpty) graphics.setTooltipForNextFrame(font, input, mouseX, mouseY)
            if (isOverTheResult && !result.isEmpty) graphics.setTooltipForNextFrame(font, result, mouseX, mouseY)
        }
    }

    private fun drawSlotWithItem(graphics: GuiGraphicsExtractor, slot: Rect, stack: ItemStack) {
        SlotSurface.draw(graphics, slot)
        graphics.item(stack, slot.x + Palette.SLOT_INSET, slot.y + Palette.SLOT_INSET)
    }

    /** Only a line that can be filled takes a click; the rest of the strip lets it through. */
    override fun isMouseOver(mouseX: Double, mouseY: Double): Boolean = clickableRowAt(mouseX, mouseY) != null

    override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) {
        val row = clickableRowAt(event.x(), event.y()) ?: return
        sendToServer(MachineFillPayload(menu.containerId, firstShown + row))
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val isOverTheStrip = Rect(x, y, width, height).contains(mouseX, mouseY)
        val hiddenRows = (menu.listed.size - rowsThatFit).coerceAtLeast(0)
        if (!isOverTheStrip || hiddenRows == 0) return false
        firstShown = (firstShown - scrollY.toInt()).coerceIn(0, hiddenRows)
        return true
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = Unit

    companion object {
        /** Between the strip and the machine's panel, as between vanilla's recipe book and its screen. */
        const val GAP = 2

        private const val PADDING = 4
        private const val INSET = Palette.BORDER + PADDING
        private const val ARROW_SPACE = 16
        private const val ROW_WIDTH = Palette.SLOT + ARROW_SPACE + Palette.SLOT
        private const val ROW_PITCH = Palette.SLOT + 2
        const val WIDTH = ROW_WIDTH + INSET * 2

        private const val ARROW = "→"
        private const val MILLIS_PER_TURN = 1000L

        /** The panel's own grey, laid over a line at most of its strength. */
        private val WASHED_OUT = 0xB0C6C6C6.toInt()
    }
}

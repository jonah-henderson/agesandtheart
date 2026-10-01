package co.voik.agesandtheart.client

import co.voik.agesandtheart.client.ui.PanelSurface
import co.voik.agesandtheart.client.ui.Palette
import co.voik.agesandtheart.client.ui.Rect
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.station.CompounderNeed
import co.voik.agesandtheart.station.Compounding
import co.voik.agesandtheart.station.CompoundingRecipeDisplay
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.util.context.ContextMap
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.crafting.display.SlotDisplayContext

/**
 * The compounder's recipe book: every compounding the player has learned, a row each — the inputs with
 * their counts, then what they make, then what beyond power the machine needs beside it. It shows rather
 * than fills, since the counts are more than vanilla's one-item-a-slot placement lays (design §7.1.2).
 *
 * A recipe a server has switched off is left out; the config is synced, so the client knows.
 */
class CompoundingBook(x: Int, y: Int) : AbstractWidget(x, y, WIDTH, HEIGHT, Component.empty()) {

    private var firstShown = 0

    private val known: List<CompoundingRecipeDisplay>
        get() {
            val book = Minecraft.getInstance().player?.recipeBook ?: return emptyList()
            return book.getCollection(Compounding.BOOK_CATEGORY)
                .flatMap { it.recipes }
                .mapNotNull { it.display() as? CompoundingRecipeDisplay }
                .filter { it.isAllowed }
        }

    override fun extractWidgetRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        PanelSurface.RAISED.draw(graphics, Rect(x, y, width, height))
        val font = Minecraft.getInstance().font
        graphics.text(font, TITLE, x + MARGIN, y + MARGIN, Palette.TEXT, false)
        val recipes = known
        if (recipes.isEmpty()) {
            val lines = font.split(NOTHING_LEARNED, width - MARGIN * 2)
            lines.forEachIndexed { line, text ->
                graphics.text(font, text, x + MARGIN, y + FIRST_ROW_Y + line * font.lineHeight, Palette.FAINT, false)
            }
            return
        }
        firstShown = firstShown.coerceIn(0, (recipes.size - ROWS_SHOWN).coerceAtLeast(0))
        val context = Minecraft.getInstance().level?.let(SlotDisplayContext::fromLevel) ?: return
        recipes.drop(firstShown).take(ROWS_SHOWN).forEachIndexed { row, recipe ->
            drawRow(graphics, recipe, context, y + FIRST_ROW_Y + row * ROW_HEIGHT, mouseX, mouseY)
        }
    }

    private fun drawRow(
        graphics: GuiGraphicsExtractor,
        recipe: CompoundingRecipeDisplay,
        context: ContextMap,
        rowY: Int,
        mouseX: Int,
        mouseY: Int,
    ) {
        val font = Minecraft.getInstance().font
        var column = x + MARGIN
        for ((input, count) in recipe.inputs.zip(recipe.counts)) {
            val stack = input.resolveForFirstStack(context).copyWithCount(count)
            drawItem(graphics, stack, column, rowY, mouseX, mouseY)
            column += Palette.SLOT
        }
        val arrowX = x + MARGIN + INPUTS_SHOWN * Palette.SLOT
        graphics.text(font, ARROW, arrowX, rowY + (Palette.ITEM - font.lineHeight) / 2 + 1, Palette.TEXT, false)
        val resultX = arrowX + ARROW_WIDTH
        drawItem(graphics, recipe.resultShown.resolveForFirstStack(context), resultX, rowY, mouseX, mouseY)
        var needX = resultX + Palette.SLOT + NEEDS_GAP
        for (need in recipe.needs - CompounderNeed.POWER) {
            drawItem(graphics, iconFor(need), needX, rowY, mouseX, mouseY, needTooltip(need))
            needX += Palette.SLOT
        }
    }

    private fun drawItem(
        graphics: GuiGraphicsExtractor,
        stack: ItemStack,
        itemX: Int,
        itemY: Int,
        mouseX: Int,
        mouseY: Int,
        tooltip: Component = stack.hoverName,
    ) {
        graphics.item(stack, itemX, itemY)
        graphics.itemDecorations(Minecraft.getInstance().font, stack, itemX, itemY)
        val isHovered = mouseX in itemX..<itemX + Palette.ITEM && mouseY in itemY..<itemY + Palette.ITEM
        if (isHovered) graphics.setTooltipForNextFrame(tooltip, mouseX, mouseY)
    }

    private fun iconFor(need: CompounderNeed): ItemStack = when (need) {
        CompounderNeed.POWER -> ItemStack(AgeContent.ARC_CRYSTAL_BLOCK)
        CompounderNeed.HEAT -> ItemStack(Items.MAGMA_BLOCK)
        CompounderNeed.COLD -> ItemStack(AgeContent.RIME_CRYSTALS.values.first())
    }

    private fun needTooltip(need: CompounderNeed): Component =
        Component.translatable(
            "container.agesandtheart.fusion_compounder.needs",
            Component.translatable("container.agesandtheart.fusion_compounder.need.${need.serializedName}"),
        )

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (!visible || !isMouseOver(mouseX, mouseY)) return false
        firstShown -= scrollY.toInt()
        return true
    }

    /** Taken, so it falls through to nothing behind it, but without a button's click: it is a page. */
    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean =
        visible && isMouseOver(event.x, event.y)

    override fun updateWidgetNarration(output: NarrationElementOutput) = Unit

    companion object {
        /** Wide enough for four inputs, the arrow, the result and two needs. */
        const val WIDTH = 160
        const val HEIGHT = 166

        /** Whether the book was left open, so the next compounder opens the same way. */
        var isOpen = true

        private const val MARGIN = 7
        private const val FIRST_ROW_Y = 20
        private const val ROW_HEIGHT = 20
        private const val ROWS_SHOWN = 7
        private const val INPUTS_SHOWN = 4
        private const val ARROW_WIDTH = 12
        private const val NEEDS_GAP = 4

        private const val ARROW = "→"

        private val TITLE: Component = Component.translatable("container.agesandtheart.fusion_compounder.book")
        private val NOTHING_LEARNED: Component =
            Component.translatable("container.agesandtheart.fusion_compounder.book.nothing_learned")
    }
}

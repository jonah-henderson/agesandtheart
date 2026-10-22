package co.voik.agesandtheart.content

import net.minecraft.world.inventory.tooltip.TooltipComponent
import net.minecraft.world.item.ItemStack

/**
 * What a notebook's tooltip shows: its [pages], newest first, and the one scrolled to. Drawn by the client as a grid
 * of [ROWS] rows of [COLUMNS], a page of them at a time.
 */
data class NotebookTooltip(val pages: List<ItemStack>, val selected: Int?) : TooltipComponent {

    /** The first page of the grid's page that holds the one scrolled to — the grid turns a whole page at its edge. */
    val firstShown: Int get() = selected?.let { it / CELLS * CELLS } ?: 0

    companion object {
        const val COLUMNS = 4
        const val ROWS = 3
        const val CELLS = COLUMNS * ROWS
    }
}

package co.voik.agesandtheart.client

import co.voik.agesandtheart.client.ui.Palette
import co.voik.agesandtheart.client.ui.Rect
import co.voik.agesandtheart.client.ui.TabStrip
import co.voik.agesandtheart.desk.DeskSlots

/**
 * The desk's fixed points, and nothing else.
 *
 * What is left here is only what a layout cannot produce: the panel itself, the strip above it, and the
 * slots — which are literal because the menu places real ones at the same coordinates on the server, where
 * no layout exists (see [DeskSlots]). Everything between those anchors is arranged by a
 * [FlexColumn][co.voik.agesandtheart.client.ui.FlexColumn], and no longer appears as a number anywhere.
 *
 * Rectangles are in screen pixels. The exception is [inventoryLabelY], which vanilla draws inside a pose
 * translated to the panel's origin.
 */
class DeskLayout(private val left: Int, private val top: Int) {

    val panel = Rect(left, top, DeskSlots.PANEL_WIDTH, DeskSlots.PANEL_HEIGHT)

    val tabsX = left
    val tabsY = top - TabStrip.LIFT

    /**
     * The room a tab arranges its contents in.
     *
     * Each tab stops where its own anchored furniture begins: the book tab above the binding row, supplies
     * above the inventory's label, and the two list-only tabs at the panel's bottom border.
     */
    fun content(tab: DeskTab): Rect {
        val bottom = when (tab) {
            DeskTab.WRITE_BOOK -> DeskSlots.BINDING_ROW_Y - GAP
            DeskTab.SUPPLIES -> DeskSlots.INVENTORY_LABEL_Y - GAP
            DeskTab.ARCHIVE, DeskTab.WRITE_PAGE -> DeskSlots.PANEL_HEIGHT - Palette.BORDER - GAP
        }
        return Rect(
            left + INSET, top + CONTENT_TOP,
            DeskSlots.PANEL_WIDTH - INSET * 2, bottom - CONTENT_TOP,
        )
    }

    /** The binding row's own line, which the name and its button share with the two slots. */
    fun bindingRow(): Rect = Rect(
        left + INSET, top + DeskSlots.BINDING_ROW_Y + BINDING_TEXT_DROP,
        DeskSlots.PANEL_WIDTH - INSET * 2, LINE,
    )

    // The item areas the menu addresses, brought into screen coordinates.
    val intakeSlot = itemArea(DeskSlots.INTAKE_X, DeskSlots.INTAKE_Y)
    val outputSlot = itemArea(DeskSlots.OUTPUT_X, DeskSlots.OUTPUT_Y)
    val playerInventory = itemArea(DeskSlots.INVENTORY_X, DeskSlots.INVENTORY_Y)

    private fun itemArea(x: Int, y: Int) = Rect(left + x, top + y, Palette.ITEM, Palette.ITEM)

    /** The slots a tab shows, which is exactly the set the menu makes active for it. */
    fun slotsOn(tab: DeskTab): List<Rect> = when (tab) {
        DeskTab.SUPPLIES -> listOf(intakeSlot)
        DeskTab.WRITE_BOOK -> listOf(outputSlot)
        DeskTab.ARCHIVE, DeskTab.WRITE_PAGE -> emptyList()
    }

    /** Off-screen where there is no inventory to label, so the words can go where the label was. */
    fun inventoryLabelY(tab: DeskTab): Int =
        if (tab.showsInventory) DeskSlots.INVENTORY_LABEL_Y else OFFSCREEN

    companion object {
        const val WIDTH = DeskSlots.PANEL_WIDTH
        const val HEIGHT = DeskSlots.PANEL_HEIGHT

        /** Clear of the panel's border, and below where the title is drawn. */
        private const val INSET = 8
        private const val CONTENT_TOP = 18
        private const val GAP = 4
        private const val LINE = 12

        /** Text sits two pixels down on a row sized for slots, so it centres against them. */
        private const val BINDING_TEXT_DROP = 2

        /** Far enough down that the label is simply not drawn. */
        private const val OFFSCREEN = 10_000

        /** How many pages the work surface fits across, which sets how big a page can be drawn. */
        const val SURFACE_COLUMNS = 3
        const val CELL_HEIGHT = 44
        const val GUTTER_HEIGHT = 10
    }
}

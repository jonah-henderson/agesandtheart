package co.voik.agesandtheart.client

import co.voik.agesandtheart.client.ui.Palette
import co.voik.agesandtheart.client.ui.Rect
import co.voik.agesandtheart.client.ui.TabStrip
import co.voik.agesandtheart.desk.DeskSlots
import co.voik.agesandtheart.desk.DeskTab

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

    /** The panel's top-left, which no tab moves — what the wing hangs off. */
    val panelX = left
    val panelY = top

    /**
     * The panel on [tab]. Only its foot varies: the bind screen has nothing to put in the room the others
     * need, so it stops level with a wing's rather than running on into empty wood (see [DeskSlots]).
     */
    fun panel(tab: DeskTab): Rect = Rect(left, top, DeskSlots.PANEL_WIDTH, panelHeight(tab))

    private fun panelHeight(tab: DeskTab): Int = when (tab) {
        DeskTab.BIND -> DeskSlots.BIND_PANEL_HEIGHT
        DeskTab.ARCHIVE, DeskTab.WRITE_BOOK -> DeskSlots.PANEL_HEIGHT
    }

    val tabsX = left
    val tabsY = top - TabStrip.LIFT

    /**
     * The room a tab arranges its contents in.
     *
     * Each tab stops where its own anchored furniture begins: the bind screen at the foot of the name box
     * it ends with, the archive above the inventory's label, and the work surface at the panel's bottom
     * border, having neither.
     */
    fun content(tab: DeskTab): Rect {
        val bottom = when (tab) {
            DeskTab.BIND -> DeskSlots.BIND_NAME_Y + LINE
            DeskTab.ARCHIVE -> DeskSlots.INVENTORY_LABEL_Y - GAP
            DeskTab.WRITE_BOOK -> DeskSlots.PANEL_HEIGHT - Palette.BORDER - GAP
        }
        return Rect(
            left + INSET, top + CONTENT_TOP,
            DeskSlots.PANEL_WIDTH - INSET * 2, bottom - CONTENT_TOP,
        )
    }

    /** The bind button, sitting level with the slot the book lands in beside it. */
    fun bindButton(): Rect = Rect(
        left + DeskSlots.BIND_BUTTON_X, top + DeskSlots.BIND_ROW_Y + BUTTON_DROP,
        DeskSlots.BIND_BUTTON_WIDTH, LINE,
    )

    // The item areas the menu addresses, brought into screen coordinates.
    val outputSlot = itemArea(DeskSlots.OUTPUT_X, DeskSlots.OUTPUT_Y)
    val playerInventory = itemArea(DeskSlots.INVENTORY_X, DeskSlots.INVENTORY_Y)

    private fun itemArea(x: Int, y: Int) = Rect(left + x, top + y, Palette.ITEM, Palette.ITEM)

    /** The slots a tab shows, which is exactly the set the menu makes active for it. */
    fun slotsOn(tab: DeskTab): List<Rect> = when (tab) {
        DeskTab.BIND -> listOf(outputSlot)
        DeskTab.ARCHIVE, DeskTab.WRITE_BOOK -> emptyList()
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

        /** A line-high button on a row sized for slots, so the two centre against each other. */
        private const val BUTTON_DROP = (Palette.ITEM - LINE) / 2

        /** Far enough down that the label is simply not drawn. */
        private const val OFFSCREEN = 10_000

        /** How many pages the work surface fits across, which sets how big a page can be drawn. */
        const val SURFACE_COLUMNS = 3
        const val CELL_HEIGHT = 44
        const val GUTTER_HEIGHT = 10
    }
}

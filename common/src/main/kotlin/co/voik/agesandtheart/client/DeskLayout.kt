package co.voik.agesandtheart.client

import co.voik.agesandtheart.client.ui.Palette
import co.voik.agesandtheart.client.ui.Rect
import co.voik.agesandtheart.client.ui.TabStrip
import co.voik.agesandtheart.desk.DeskSlots

/**
 * Where everything on the writer's desk goes.
 *
 * Pure geometry: nothing here draws and nothing here decides. Rebuilt whenever the screen is, so each
 * position is stated once and read from here by whatever needs it.
 *
 * Slot positions are **not** here — they are [DeskSlots], because the menu needs them too and it cannot see
 * client code. This turns them into screen coordinates and nothing more.
 *
 * Rectangles are in screen pixels, because that is what widgets are placed in. The one exception is
 * [inventoryLabelY], which vanilla draws inside a pose translated to the panel's origin.
 */
class DeskLayout(private val left: Int, private val top: Int) {

    val panel = Rect(left, top, WIDTH, HEIGHT)

    val tabsX = left
    val tabsY = top - TabStrip.LIFT

    val search = Rect(left + INNER_X, top + SEARCH_Y, INNER_WIDTH, LINE)

    /** Always full width. On the book tab it is short, because the work surface sits under it. */
    fun wordList(tab: DeskTab): Rect =
        Rect(left + INNER_X, top + LIST_TOP, INNER_WIDTH, listBottom(tab) - LIST_TOP)

    /**
     * Where the book is laid out.
     *
     * Full width because the cells have to be big enough for the script to read, and three of them plus a
     * scrollbar is what 160 pixels buys.
     */
    val workSurface = Rect(
        left + INNER_X, top + SURFACE_TOP,
        INNER_WIDTH, SURFACE_BOTTOM - SURFACE_TOP,
    )

    val compositionHeader = Rect(left + INNER_X, top + COMPOSE_HEADER_Y, INNER_WIDTH, LINE)

    fun paperButton(index: Int) = Rect(
        left + INNER_X + index * (PAPER_BUTTON_WIDTH + GAP), top + BUTTON_Y,
        PAPER_BUTTON_WIDTH, LINE + 2,
    )

    /** The ink a paper choice would cost, printed under its button. */
    fun priceLabel(index: Int) = Rect(
        left + INNER_X + index * (PAPER_BUTTON_WIDTH + GAP), top + BUTTON_Y + LINE + PRICE_DROP,
        PAPER_BUTTON_WIDTH, LINE,
    )

    val nameBox = Rect(left + INNER_X, top + NAME_Y, NAME_WIDTH, LINE)

    val bindButton = Rect(nameBox.right + GAP, top + NAME_Y, BIND_WIDTH, LINE)

    // The item areas the menu addresses, brought into screen coordinates.
    val intakeSlot = itemArea(DeskSlots.INTAKE_X, DeskSlots.INTAKE_Y)
    val bindingSlot = itemArea(DeskSlots.BINDING_X, DeskSlots.BINDING_Y)
    val outputSlot = itemArea(DeskSlots.OUTPUT_X, DeskSlots.OUTPUT_Y)
    val playerInventory = itemArea(DeskSlots.INVENTORY_X, DeskSlots.INVENTORY_Y)

    private fun itemArea(x: Int, y: Int) = Rect(left + x, top + y, Palette.ITEM, Palette.ITEM)

    /** The slots a tab shows, which is exactly the set the menu makes active for it. */
    fun slotsOn(tab: DeskTab): List<Rect> = when (tab) {
        DeskTab.SUPPLIES -> listOf(intakeSlot)
        DeskTab.WRITE_BOOK -> listOf(bindingSlot, outputSlot)
        DeskTab.ARCHIVE, DeskTab.WRITE_PAGE -> emptyList()
    }

    /** Off-screen where there is no inventory to label, so the words can go where the label was. */
    fun inventoryLabelY(tab: DeskTab): Int =
        if (tab.showsInventory) HEIGHT - INVENTORY_LABEL_LIFT else OFFSCREEN

    private fun listBottom(tab: DeskTab): Int = when (tab) {
        DeskTab.ARCHIVE -> ARCHIVE_LIST_BOTTOM
        DeskTab.WRITE_PAGE -> PAGE_LIST_BOTTOM
        DeskTab.WRITE_BOOK -> BOOK_LIST_BOTTOM
        DeskTab.SUPPLIES -> LIST_TOP
    }

    companion object {
        /**
         * A chest's width, and half again its height.
         *
         * Taller than a container because the book tab stacks four things that all need room — a source
         * list, the work surface, the binding controls and the player's own inventory. The width stays a
         * chest's so the inventory block sits in it exactly as it does everywhere else.
         */
        const val WIDTH = 176
        const val HEIGHT = 270

        private const val INNER_X = 8
        private const val INNER_WIDTH = 160
        private const val LINE = 12
        private const val GAP = 2

        private const val SEARCH_Y = 20
        private const val LIST_TOP = 36

        // Each tab keeps a different amount of room below the list for its own controls. The book tab keeps
        // the most by far, because everything else it needs is under the list rather than beside it.
        private const val ARCHIVE_LIST_BOTTOM = 252
        private const val PAGE_LIST_BOTTOM = 226
        private const val BOOK_LIST_BOTTOM = 72

        /**
         * The book tab, under its word list: the work surface, then the binding row, then the inventory.
         *
         * Sixty pixels holds one row of cells and gutters with six left over, and the peek is deliberate —
         * a surface cut exactly to one row looks like the whole book rather than the top of it.
         */
        private const val SURFACE_TOP = 88
        private const val SURFACE_BOTTOM = 148

        private const val BUTTON_Y = 232
        private const val PAPER_BUTTON_WIDTH = 30
        private const val PRICE_DROP = 4

        /** How many pages the work surface fits across, which sets how big a page can be drawn. */
        const val SURFACE_COLUMNS = 3
        const val CELL_HEIGHT = 44
        const val GUTTER_HEIGHT = 10

        private const val COMPOSE_HEADER_Y = 76

        private const val NAME_Y = 152
        private const val NAME_WIDTH = 60
        private const val BIND_WIDTH = 30

        private const val INVENTORY_LABEL_LIFT = 94

        /** Far enough down that the label is simply not drawn. */
        private const val OFFSCREEN = 10_000
    }
}

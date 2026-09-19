package co.voik.agesandtheart.desk

/**
 * A desk wing's panel size, and where its slots sit in it — the ink case and the supply bin.
 *
 * **This is the one part of those screens that cannot be laid out**: the menu places real `Slot`s at these
 * coordinates on the server, where no client layout class exists, so a position both sides must agree on
 * has to be a number both sides can read. The desk's own screen has no slots at all.
 *
 * Coordinates are the item's 16×16 area relative to the panel's origin, which is what `Slot` takes.
 */
object DeskSlots {
    const val PANEL_WIDTH = 176

    const val INVENTORY_COLUMNS = 9
    const val INVENTORY_ROWS = 3

    /** Three rows of slots, then vanilla's four-pixel gap before the hotbar. */
    const val HOTBAR_DROP = INVENTORY_ROWS * 18 + 4

    const val INVENTORY_X = 8

    /**
     * A three-row container's height, because that is what a wing holds. 83 is what a chest leaves below
     * its inventory's first row, so the block reads as the one every other screen shows.
     */
    const val WING_PANEL_HEIGHT = 166
    const val WING_INVENTORY_Y = WING_PANEL_HEIGHT - 83
    const val WING_HOTBAR_Y = WING_INVENTORY_Y + HOTBAR_DROP
    const val WING_INVENTORY_LABEL_Y = WING_INVENTORY_Y - 11

    /**
     * The doorway sits in the **top corner**, out of the way of what a wing is showing.
     *
     * It was centred in the room above the inventory, which is where the tanks and the stock lines also
     * wanted to be — so the two drew over each other. Only one of them can have the middle, and it is not
     * the slot: a gauge is what a wing is *for*, and a doorway is a place to drop things.
     */
    const val WING_INTAKE_X = 152
    const val WING_INTAKE_Y = 20
}

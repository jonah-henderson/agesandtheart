package co.voik.agesandtheart.desk

/**
 * The desk's panel size, and where its slots sit in it.
 *
 * **This is the one part of the screen that cannot be laid out**, and the reason is worth knowing: the
 * menu places real `Slot`s at these coordinates on the server, where no client layout class exists. A
 * position both sides must agree on has to be a number both sides can read. Everything else on the desk is
 * arranged by [FlexColumn][co.voik.agesandtheart.client.ui.FlexColumn] and states nothing.
 *
 * So the numbers here are kept few and derived from [PANEL_HEIGHT] rather than written out, and the client
 * reads them as anchors instead of repeating them. Changing the panel's height moves the slots with it.
 *
 * Coordinates are the item's 16×16 area relative to the panel's origin, which is what `Slot` takes.
 */
object DeskSlots {
    const val PANEL_WIDTH = 176

    /** A large chest's, which is as tall as the game's default GUI scale has room for. */
    const val PANEL_HEIGHT = 222

    const val INVENTORY_COLUMNS = 9
    const val INVENTORY_ROWS = 3

    /** Three rows of slots, then vanilla's four-pixel gap before the hotbar. */
    const val HOTBAR_DROP = INVENTORY_ROWS * 18 + 4

    /**
     * The player's own, sitting the same distance off the bottom as any container's.
     *
     * 83 is what a chest leaves below its inventory's first row — keeping it means the block reads as the
     * one every other screen shows, at whatever height this panel happens to be.
     */
    const val INVENTORY_X = 8
    const val INVENTORY_Y = PANEL_HEIGHT - 83
    const val HOTBAR_Y = INVENTORY_Y + HOTBAR_DROP

    /** The label above the player's inventory, which is also where the desk's own contents must stop. */
    const val INVENTORY_LABEL_Y = INVENTORY_Y - 11

    /**
     * The book being produced, beside the name it is given.
     *
     * Anchored to the panel's foot rather than to the inventory label: the book tab has no inventory, so
     * the row sits as low as the bottom border allows.
     */
    const val BINDING_ROW_Y = PANEL_HEIGHT - 32
    const val OUTPUT_X = 142
    const val OUTPUT_Y = BINDING_ROW_Y

    /** The general doorway, centred in the room the supplies tab has above the inventory. */
    const val INTAKE_X = 79
    const val INTAKE_Y = (18 + INVENTORY_LABEL_Y) / 2 - 8
}

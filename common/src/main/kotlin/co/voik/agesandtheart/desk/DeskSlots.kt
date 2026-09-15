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
     * A wing's panel — the ink case and the supply bin, which each show stores and one doorway.
     *
     * A three-row container's height, because that is what they hold: nothing here needs the room the
     * centre ran out of, and a short panel says so before it is read.
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

    /**
     * **The bind screen is shorter than the others**, stopping level with a wing's panel.
     *
     * It shows a sentence, a name and a button, and none of those wanted the room a chest's worth of
     * inventory needs — so the panel ran on into a hand's breadth of empty wood under the book. The top is
     * where every other tab's is, so switching tabs moves nothing; only the foot comes up.
     */
    const val BIND_PANEL_HEIGHT = WING_PANEL_HEIGHT

    /**
     * The book being produced, beside the button that makes it, and the name above them both.
     *
     * Centred as a cluster rather than pushed to the edges, being one action and its one result.
     * [BIND_BUTTON_X] and [BIND_BUTTON_WIDTH] are written down *here*, beside the slot, because that slot
     * is placed on the server — where nothing knows how wide a button is, and where centring the pair
     * could not otherwise be worked out.
     */
    const val BIND_ROW_Y = BIND_PANEL_HEIGHT - 26
    const val BIND_BUTTON_X = 43
    const val BIND_BUTTON_WIDTH = 60
    const val OUTPUT_X = 117
    const val OUTPUT_Y = BIND_ROW_Y

    /** Full width under the readout, a line and a gap above the button. */
    const val BIND_NAME_Y = BIND_ROW_Y - 16
}

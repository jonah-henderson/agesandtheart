package co.voik.agesandtheart.desk

/**
 * Where the desk's slots are.
 *
 * Stated once because two places need it and they have to agree: [WritersDeskMenu] puts the real `Slot`s
 * here, and the screen puts a recess behind each. A slot drawn somewhere other than where it can be clicked
 * is the classic container-screen defect, and it is only reachable if the number is written down twice.
 *
 * Coordinates are the item's 16×16 area relative to the panel's origin, which is what `Slot` takes. This
 * stays clear of client code so the menu can use it on a dedicated server.
 */
object DeskSlots {
    /** The general doorway: everything handed to the desk except the binding. */
    const val INTAKE_X = 79
    const val INTAKE_Y = 96

    /** The binding, and the book it produces — beside the name it is being given. */
    const val BINDING_X = 116
    const val BINDING_Y = 151
    const val OUTPUT_X = 142
    const val OUTPUT_Y = 151

    /** The player's own, in vanilla's arrangement, sitting the same distance off the bottom as a chest's. */
    const val INVENTORY_X = 8
    const val INVENTORY_Y = 187
    const val INVENTORY_ROWS = 3
    const val INVENTORY_COLUMNS = 9

    /** Three rows of slots, then vanilla's four-pixel gap before the hotbar. */
    const val HOTBAR_DROP = INVENTORY_ROWS * 18 + 4
    const val HOTBAR_Y = INVENTORY_Y + HOTBAR_DROP
}

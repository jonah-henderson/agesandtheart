package co.voik.agesandtheart.client.ui

/**
 * Vanilla's GUI colours, sampled from the textures named beside them rather than guessed.
 *
 * Values only — nothing here draws. What uses them is a [Decoration].
 */
object Palette {
    // generic_54.png, the double chest's panel.
    val PANEL = 0xFFC6C6C6.toInt()
    val OUTLINE = 0xFF000000.toInt()
    val HIGHLIGHT = 0xFFFFFFFF.toInt()
    val SHADOW = 0xFF555555.toInt()

    // The recess a slot or a gauge sits in.
    val WELL_EDGE = 0xFF373737.toInt()
    val WELL = 0xFF8B8B8B.toInt()

    val TEXT = 0xFF404040.toInt()
    val FAINT = 0xFF808080.toInt()
    val WARNING = 0xFF8B2E2E.toInt()

    val SELECTION = 0x60000000
    val HOVER = 0x30000000

    /**
     * How wide a panel's border is: one pixel of outline plus two of bevel.
     *
     * Measured off `generic_54.png` — `x=0` black, `x=1..2` white, `C6` from `x=3`.
     */
    const val BORDER = 3

    /** A slot's recess is 18 square; the item inside it is 16, inset by one. */
    const val SLOT = 18
    const val SLOT_INSET = 1
    const val ITEM = 16
}

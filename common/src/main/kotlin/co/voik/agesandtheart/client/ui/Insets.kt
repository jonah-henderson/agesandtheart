package co.voik.agesandtheart.client.ui

/**
 * Space held clear on each side of something.
 *
 * The four sides are separate: a bordered panel rarely wants the same gap on all of them. Vanilla's
 * `LayoutSettings` carries padding only inside a layout, so it cannot be held as a value.
 */
data class Insets(val left: Int, val top: Int, val right: Int, val bottom: Int) {

    val horizontal: Int get() = left + right
    val vertical: Int get() = top + bottom

    companion object {
        val NONE = Insets(0, 0, 0, 0)

        fun all(amount: Int) = Insets(amount, amount, amount, amount)

        fun symmetric(horizontal: Int, vertical: Int) =
            Insets(horizontal, vertical, horizontal, vertical)
    }
}

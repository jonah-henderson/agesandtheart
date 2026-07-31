package co.voik.agesandtheart.client.ui

/**
 * Space held clear on each side of something.
 *
 * Ours rather than vanilla's because `LayoutSettings` carries padding only *inside* a layout, for one child
 * of it — there is no standalone value a [DecoratedBox] can hold. The four sides are separate because a
 * bordered panel rarely wants the same gap on all of them: a border eats into one side and not the others.
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

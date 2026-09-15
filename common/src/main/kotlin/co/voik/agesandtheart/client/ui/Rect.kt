package co.voik.agesandtheart.client.ui

/**
 * A rectangle in screen pixels.
 *
 * The vocabulary layout speaks and widgets are placed in, so that a position is computed once and passed
 * around rather than re-derived by whoever needs it next.
 */
data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height

    fun contains(pointX: Double, pointY: Double): Boolean =
        pointX >= x && pointX < right && pointY >= y && pointY < bottom

    fun inset(by: Int): Rect = Rect(x + by, y + by, width - by * 2, height - by * 2)
}

/** Which side of a [Rect] a piece of chrome leaves unbordered. */
enum class Edge { LEFT, RIGHT, TOP, BOTTOM }

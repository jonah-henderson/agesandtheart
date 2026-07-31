package co.voik.agesandtheart.client.ui

/**
 * A widget that has to do something when a layout changes its size.
 *
 * `AbstractWidget.setSize` only stores the numbers. Anything holding its own arranged contents — a list
 * with entries, a surface with rows — has to re-place them, and no vanilla hook says when that happened.
 */
fun interface Resized {
    fun onResized(bounds: Rect)
}

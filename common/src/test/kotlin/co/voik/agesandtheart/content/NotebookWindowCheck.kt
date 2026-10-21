package co.voik.agesandtheart.content

import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** A notebook's tooltip grid moves only when the page scrolled to would leave it, by as few rows as it takes. */
@Tags(NEEDS_REGISTRIES)
class NotebookWindowCheck : FunSpec({

    fun windowsScrollingThrough(pages: Int, order: Iterable<Int>): List<Int> =
        order.runningFold(0 to 0) { (_, first), selected -> selected to NotebookItem.windowAround(selected, first, pages) }
            .drop(1).map { it.second }

    test("scrolling down, the grid stays put until the highlight passes its last page") {
        val windows = windowsScrollingThrough(PAGES, 0..<PAGES)
        check(windows.take(SHOWN_AT_FIRST) == List(SHOWN_AT_FIRST) { 0 }) { "the grid moved before its edge: $windows" }
        check(windows[SHOWN_AT_FIRST] == ROW) { "the grid did not move a row at its edge: $windows" }
        check(windows.zipWithNext().all { (before, after) -> after - before in setOf(0, ROW) }) { "the grid jumped: $windows" }
    }

    test("scrolling back up, the grid stays put until the highlight passes its first page") {
        val down = windowsScrollingThrough(PAGES, 0..<PAGES).last()
        val up = (PAGES - 1 downTo 0).runningFold(down) { first, selected -> NotebookItem.windowAround(selected, first, PAGES) }.drop(1)
        check(up.first() == down) { "turning back moved the grid: $up" }
        check(up.last() == 0) { "the grid did not come back to the start: $up" }
        val firstMove = up.indexOfFirst { it != down }
        check(PAGES - 1 - firstMove == down - 1) { "the grid moved before the highlight reached its top: $up" }
    }

    test("a notebook that fits shows from its first page wherever it is scrolled") {
        check((0..<FITS).all { NotebookItem.windowAround(it, 0, FITS) == 0 }) { "a notebook of $FITS pages scrolled" }
    }
}) {
    private companion object {
        const val PAGES = 30
        const val FITS = 12
        const val ROW = 4

        /** Vanilla's grid over thirty: eleven cells for pages, less the two its part row leaves empty. */
        const val SHOWN_AT_FIRST = 9
    }
}

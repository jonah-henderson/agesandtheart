package co.voik.agesandtheart.content

import io.kotest.core.spec.style.FunSpec

/** A notebook's tooltip grid turns a whole page of twelve at its edge, and shows from the start with nothing scrolled to. */
class NotebookTooltipCheck : FunSpec({

    fun firstShownWith(selected: Int?) = NotebookTooltip(emptyList(), selected).firstShown

    test("the grid holds still while the highlight crosses it, and turns a page past its edge") {
        check((0..<NotebookTooltip.CELLS).all { firstShownWith(it) == 0 }) { "the grid moved inside its first page" }
        check(firstShownWith(NotebookTooltip.CELLS) == NotebookTooltip.CELLS) { "the grid did not turn at its edge" }
        check(firstShownWith(NotebookTooltip.CELLS * 2 - 1) == NotebookTooltip.CELLS) { "the grid turned early" }
        check(firstShownWith(null) == 0) { "a notebook with nothing scrolled to did not show from its start" }
    }
})

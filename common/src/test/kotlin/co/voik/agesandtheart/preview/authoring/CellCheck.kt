package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.preview.authoring.ui.cell
import io.kotest.core.spec.style.FunSpec

/**
 * How a table cell is fitted to its column.
 *
 * The sliding is the part worth checking: filtering reads every column now, so a row can be on screen
 * because of something sixty characters into its effects — and a match cut off by the column is a row
 * with no visible reason to be there.
 */
class CellCheck : FunSpec({

    test("a short value is padded to the column") {
        check(cell("red", 6) == "red   ") { "got '${cell("red", 6)}'" }
    }

    test("a long value is cut with an ellipsis") {
        check(cell("abcdefghij", 5) == "abcd…") { "got '${cell("abcdefghij", 5)}'" }
    }

    test("a match already visible does not move the view") {
        check(cell("abcdefghij", 5, "abc") == "abcd…") { "got '${cell("abcdefghij", 5, "abc")}'" }
    }

    test("a match past the cut slides into view") {
        val shown = cell("colour=red mingling=0.5 stone=basalt", 12, "basalt")
        check(shown.contains("basalt")) { "the match was cut off: '$shown'" }
        check(shown.length == 12) { "the column was not held at 12: '$shown' is ${shown.length}" }
    }

    test("a match nothing holds is left alone") {
        check(cell("abcdefghij", 5, "zzz") == "abcd…") { "got '${cell("abcdefghij", 5, "zzz")}'" }
    }
})

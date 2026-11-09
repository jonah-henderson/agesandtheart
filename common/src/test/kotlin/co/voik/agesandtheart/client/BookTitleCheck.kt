package co.voik.agesandtheart.client

import io.kotest.core.spec.style.FunSpec

/** A book's name is set as large as fits its front board or spine, and cut short only when the smallest will not do. */
class BookTitleCheck : FunSpec({

    /** Every character six font units wide, as most of the default font's are. */
    val widthOf = { text: String -> text.length * CHARACTER_WIDTH }

    fun fitted(title: String, area: BookTitle.Area) = BookTitle.fit(title, area, LINE_HEIGHT, widthOf)

    fun fitsTheArea(fitted: BookTitle.Fitted, area: BookTitle.Area): Boolean {
        val widest = fitted.lines.maxOf(widthOf) * fitted.scale
        val tall = fitted.lines.size * LINE_HEIGHT * fitted.scale
        return widest <= area.width + TOLERANCE && tall <= area.height + TOLERANCE && fitted.lines.size <= area.mostLines
    }

    test("a short name stands at the largest size, whole") {
        val fitted = fitted("Riven", BookTitle.FRONT)
        check(fitted.lines == listOf("Riven") && fitted.scale == BookTitle.FRONT.largestLine / LINE_HEIGHT) { "set as $fitted" }
    }

    test("a longer name wraps between words and shrinks to fit, whole") {
        val fitted = fitted("The Age of Falling Water", BookTitle.FRONT)
        check(fitted.lines.joinToString(" ") == "The Age of Falling Water") { "set as $fitted" }
        check(fitsTheArea(fitted, BookTitle.FRONT)) { "$fitted overflows the front" }
    }

    for (area in listOf(BookTitle.FRONT, BookTitle.SPINE)) {
        test("a name too long for any size is cut short with an ellipsis, and still fits (${area.mostLines} line)") {
            val fitted = fitted("An Exceedingly Long Name Given At An Anvil By Somebody Who Had A Great Deal To Say", area)
            check(fitted.lines.last().endsWith("…")) { "not cut short: $fitted" }
            check(fitsTheArea(fitted, area)) { "$fitted overflows" }
        }
    }

    for (title in listOf("Tomahna", "Releeshahn", "Kortee'nea", "The Great Shaft")) {
        test("'$title' shrinks rather than break a word") {
            for (area in listOf(BookTitle.FRONT, BookTitle.SPINE)) {
                val fitted = fitted(title, area)
                check(fitted.lines.flatMap { it.split(" ") } == title.split(" ")) { "'$title' set as $fitted" }
                check(fitsTheArea(fitted, area)) { "$fitted overflows" }
            }
        }
    }

    test("a word too long for a line is broken within it") {
        val lines = BookTitle.wrap("Gehnnnnnnnnnnnnnnnnnn", width = 30.0f, widthOf)
        check(lines.size > 1 && lines.all { widthOf(it) <= 30 }) { "wrapped as $lines" }
        check(lines.joinToString("") == "Gehnnnnnnnnnnnnnnnnnn") { "lost letters: $lines" }
    }
})

private const val CHARACTER_WIDTH = 6
private const val LINE_HEIGHT = 9
private const val TOLERANCE = 0.001f

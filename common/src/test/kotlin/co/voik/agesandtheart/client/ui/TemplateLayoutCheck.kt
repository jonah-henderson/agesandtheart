package co.voik.agesandtheart.client.ui

import io.kotest.core.spec.style.FunSpec

/** The template editor's own rows, and how a reading survives an edit. Measured a pixel a character. */
class TemplateLayoutCheck : FunSpec({

    fun rows(text: String, limit: Int, pads: Map<Int, Int> = emptyMap()) =
        wrapRows(text, limit) { 1 + (pads[it + 1] ?: 0) }.map { text.substring(it.begin, it.end) }

    test("breaks at the last space that fits, and the break is nobody's") {
        check(rows("sea and sky", 7) == listOf("sea and", "sky")) { "${rows("sea and sky", 7)}" }
    }

    test("a new line is always a row") {
        check(rows("sea\n\nsky", 20) == listOf("sea", "", "sky"))
    }

    test("a word too wide for any row is cut inside") {
        check(rows("abcdefgh", 3) == listOf("abc", "def", "gh"))
    }

    test("a word padded for its script wraps as the wider word") {
        // "sea" ends at 3 and is padded by 4, so it is seven wide and "sea and" no longer fits in 8.
        check(rows("sea and", 8) == listOf("sea and"))
        check(rows("sea and", 8, pads = mapOf(3 to 4)) == listOf("sea", "and"))
    }

    test("the empty text is one empty row") {
        check(wrapRows("", 10) { 1 } == listOf(DisplayRow(0, 0)))
    }

    val sea = TextMark(0, 3)
    val sky = TextMark(8, 11)

    test("a mark before the edit stays, and one after it moves") {
        val carried = carriedThrough(listOf(sea, sky), "sea and sky", "sea or sky")
        check(carried == listOf(sea, TextMark(7, 10))) { "$carried" }
    }

    test("typing onto the end of a word takes its reading away") {
        check(carriedThrough(listOf(sea), "sea", "seas").isEmpty())
    }

    test("a space after a word keeps its reading") {
        check(carriedThrough(listOf(sea), "sea", "sea ") == listOf(sea))
    }

    test("joining two words drops both") {
        check(carriedThrough(listOf(TextMark(0, 3), TextMark(4, 7)), "sea sky", "seasky").isEmpty())
    }
})

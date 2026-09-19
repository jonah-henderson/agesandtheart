package co.voik.agesandtheart.client.ui

import io.kotest.core.spec.style.FunSpec

/** How a list row breaks a name too long for it, measured a pixel a character. */
class WrapLabelCheck : FunSpec({

    fun wrap(label: String, width: Int, indent: Int = 2) = wrapLabel(label, width, indent) { it.length }

    test("a name that fits is one line") {
        check(wrap("copper", 10) == listOf("copper"))
    }

    test("breaks between words, and the indented lines are narrower") {
        val lines = wrap("waxed weathered copper stairs", 16)
        check(lines == listOf("waxed weathered", "copper stairs")) { "$lines" }
        lines.drop(1).forEach { check(it.length <= 14) { "'$it' overruns its indent" } }
    }

    test("a word wider than a line is broken inside it") {
        val lines = wrap("abcdefghijklmnop", 8)
        check(lines == listOf("abcdefgh", "ijklmn", "op")) { "$lines" }
    }

    test("nothing is lost") {
        val label = "the long and winding name of a word nobody could want"
        check(wrap(label, 12).joinToString(" ") == label)
    }
})

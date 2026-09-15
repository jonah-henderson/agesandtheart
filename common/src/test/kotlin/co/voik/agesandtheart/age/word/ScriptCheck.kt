package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus.vocabulary
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * The script the Art is written in, as the pack we ship describes it.
 *
 * What matters here is that **a whole line comes out the same as its words did**. A book's page is a line
 * of running prose now rather than a row of pages, and spelling it as one blob would quietly lose every
 * authored spelling — a fault that shows up as a page of slightly wrong glyphs and nothing else.
 */
@Tags(NEEDS_REGISTRIES)
class ScriptCheck : FunSpec({

    val script by lazy { vocabulary.script }

    test("the script we ship rewrites something") {
        check(script.rules.isNotEmpty()) { "the transliteration table is empty, so this spec asserts nothing" }
    }

    /** A line is its words, spelled and put back in order — nothing more and nothing between. */
    test("a line is spelled word by word") {
        val words = listOf("landmass", "of", "basalt")
        val spelled = script.spellEachWord(words.joinToString(" "))
        check(spelled == words.joinToString(" ") { script.spell(it) }) {
            "'${words.joinToString(" ")}' came out as '$spelled'"
        }
    }

    /**
     * **An authored spelling survives being in a sentence**, which is the whole reason a line is not simply
     * transliterated whole. Skipped rather than failed where the pack authors none: it is content, and a
     * pack is allowed to lean entirely on the rules.
     */
    test("an authored spelling still applies inside a line") {
        val authored = vocabulary.words.map { it.name }.firstOrNull(script::isAuthored)
            ?: return@test
        val inALine = script.spellEachWord("landmass of $authored")
        check(inALine.endsWith(script.spell(authored))) {
            "'$authored' is spelled ${script.spell(authored)} alone and '$inALine' in a line"
        }
    }

    /**
     * **An underscore becomes a break**, which is what lets a book gloss a long name part for part.
     *
     * A derived word is a block id, so `packed_ice` is two words wearing one name — and a book sets the
     * script over its reading word for word, so the two sides have to divide alike. The reading divides on
     * its own spaces; this is the half the script owes, and it is a rule in a data file rather than
     * anything code can guarantee.
     */
    test("a name divides the same on both sides") {
        val name = "packed_ice"
        val spelled = script.spellEachWord(name)
        val parts = spelled.split(BREAKS).filter { it.isNotBlank() }
        check(parts.size == name.split('_').size) {
            "'$name' is two words and spells as ${parts.size}: '$spelled'"
        }
    }

    /**
     * The punctuation a reading inserts is not part of the word it sits against — otherwise `slate,` misses
     * `slate`'s own spelling, and a comma is enough to change how a word is written.
     */
    test("punctuation does not change how a word is spelled") {
        val bare = script.spellEachWord("basalt")
        for (punctuated in listOf("basalt,", "basalt.")) {
            val spelled = script.spellEachWord(punctuated)
            check(spelled.startsWith(bare)) { "'$punctuated' was spelled '$spelled' where 'basalt' is '$bare'" }
        }
    }
})

/** Where a book is allowed to cut a name in two, on either side of the gloss. */
private val BREAKS = Regex("[\\s_]+")

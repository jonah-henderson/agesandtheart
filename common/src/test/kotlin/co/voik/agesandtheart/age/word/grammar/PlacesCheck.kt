package co.voik.agesandtheart.age.word.grammar

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus.vocabulary
import co.voik.agesandtheart.age.aspect.Aspect
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * Whether where the desk says a word applies is where the parser lets it be aimed — the two are one rule,
 * and the tooltip is the only place a writer learns it.
 */
@Tags(NEEDS_REGISTRIES)
class PlacesCheck : FunSpec({

    test("a material applies exactly where it may be aimed") {
        for (name in listOf("deepslate", "basalt", "water", "oak_sign", "packed_ice")) {
            val word = vocabulary.word(name) ?: error("'$name' is not a word")
            val places = Grammar.placesFor(word)
            for (aspect in Aspect.entries) {
                val parses = Grammar.parses(vocabulary, listOf("age", name, aspect.page))
                check(parses == aspect in places) {
                    "'$name ${aspect.page}' ${if (parses) "parses" else "does not parse"}, " +
                        "but the desk says it applies to $places"
                }
            }
        }
    }

    /** Nara's case, in a stone the offline corpus has: it reached only the parts it sets. */
    test("a stone applies wherever stone can be laid") {
        val stone = vocabulary.word("deepslate") ?: error("deepslate is not a word")
        val places = Grammar.placesFor(stone)
        check(Aspect.TERRAIN in places && Aspect.SURFACE in places && Aspect.SEA in places) { "deepslate applies to $places" }
    }
})

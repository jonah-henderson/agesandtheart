package co.voik.agesandtheart.age.word.generation

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.grammar.Grammar
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import kotlin.random.Random

/**
 * The generation grammars we actually ship, against the corpus they draw from.
 *
 * The one that matters is the subset rule: **whatever a book grammar produces, the parser must read
 * whole.** A generated book with a dropped page would make an Age vaguer for no reason its reader could
 * see, and the fault would sit in a data file rather than anywhere a stack trace points.
 */
@Tags(NEEDS_REGISTRIES)
class ShippedGrammarsCheck : FunSpec({

    val vocabulary by lazy { Vocabulary.load(MinecraftRegistries.shippedData()) }

    test("the grammars we ship load without a problem") {
        check(vocabulary.problems.isEmpty()) { "the corpus would not load: ${vocabulary.problems}" }
        check(vocabulary.generation.names.isNotEmpty()) { "no generation grammar was found at all" }
    }

    test("every book the book grammar writes parses whole") {
        val grammar = vocabulary.generation.grammar("book") ?: error("no 'book' generation grammar")
        for (seed in 1L..500L) {
            val pages = grammar.expand(Random(seed))
            val sentence = Grammar.read(vocabulary, pages)
            check(sentence.dropped.isEmpty()) { "seed $seed wrote '$pages', dropping ${sentence.dropped}" }
            check(sentence.phrases.isNotEmpty()) { "seed $seed wrote '$pages', which said nothing" }
        }
    }

    test("a name comes out of the name grammar") {
        for (seed in 1L..200L) {
            val name = AgeName.drawn(vocabulary, seed) ?: error("seed $seed drew no name")
            check(name.read.length in 2..24) { "seed $seed drew '${name.read}'" }
            check(name.read.first().isUpperCase()) { "seed $seed drew an uncapitalised '${name.read}'" }
            check(name.written.isNotEmpty()) { "seed $seed drew '${name.read}' with no spelling" }
        }
    }
})

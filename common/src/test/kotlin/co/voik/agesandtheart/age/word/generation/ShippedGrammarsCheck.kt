package co.voik.agesandtheart.age.word.generation

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.grammar.Grammar
import co.voik.agesandtheart.age.word.grammar.PageClass
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import kotlin.random.Random

/**
 * The generation grammars we actually ship, as **content**: they are data files, so what is wrong with one
 * is wrong in a place no stack trace points at.
 *
 * What the `book` grammar's output has to survive is asked by `BookCheck`, which takes it all the way
 * through the resolver. What is left here is the properties each grammar owes for being the grammar it is —
 * chiefly the repair grammar's tameness, which is a rule about *which words are in the file* and nothing
 * downstream can enforce.
 */
@Tags(NEEDS_REGISTRIES)
class ShippedGrammarsCheck : FunSpec({

    val vocabulary by lazy { Vocabulary.load(MinecraftRegistries.shippedData()) }

    test("the grammars we ship load without a problem") {
        check(vocabulary.problems.isEmpty()) { "the corpus would not load: ${vocabulary.problems}" }
        check(vocabulary.generation.names.isNotEmpty()) { "no generation grammar was found at all" }
    }

    /**
     * **The repair grammar names no material** (§8.2, and Jonah's own reason for keeping it apart from the
     * book grammar): a writer whose book does not parse gets a world the Art filled in, and if that filling
     * could name a block they would reroll a single page until it handed them an Age of diamond.
     *
     * Asked of the file rather than of an expansion, because a rule drawn one time in fifty is exactly the
     * one a sampled check misses and a player finds.
     */
    test("the repair grammar cannot hand anyone a material") {
        val grammar = vocabulary.generation.grammar("repair") ?: error("no 'repair' generation grammar")
        val materials = grammar.rules.values.flatten()
            .flatMap { it.produces }
            .filterIsInstance<Symbol.Terminal>()
            .map { Grammar.classify(vocabulary, it.text) }
            .filter { it.kind == PageClass.MATERIAL }
            .map { it.written }
        check(materials.isEmpty()) {
            "the repair grammar can produce ${materials.distinct().joinToString(" ")}, which is a block a " +
                "writer never asked for"
        }
    }

    /**
     * And it says something about **every** part of the world, which is what makes a page always have
     * somewhere to go — a skeleton missing a section strands every term belonging to it.
     */
    test("the repair grammar writes a complete world") {
        val grammar = vocabulary.generation.grammar("repair") ?: error("no 'repair' generation grammar")
        for (seed in 1L..200L) {
            val pages = grammar.expand(Random(seed)).map { Grammar.classify(vocabulary, it) }
            val aimedAt = pages.filter { it.kind == PageClass.SUBJECT }.mapNotNull { it.aspect }.toSet()
            val unspokenFor = Aspect.entries.filterNot { it in aimedAt }
            check(unspokenFor.isEmpty()) {
                "seed $seed drew a world with nothing said about ${unspokenFor.joinToString(" ") { it.key }}"
            }
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

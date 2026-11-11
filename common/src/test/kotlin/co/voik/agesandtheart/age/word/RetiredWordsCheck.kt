package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.SkyBodies
import co.voik.agesandtheart.age.language.Fate
import co.voik.agesandtheart.location
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** A retired word still reads, as what it became, so no saved page or learned word is stranded. */
@Tags(NEEDS_REGISTRIES)
class RetiredWordsCheck : FunSpec({

    val vocabulary by lazy { Vocabulary.load(MinecraftRegistries.shippedData(), MinecraftRegistries.worldgen) }

    test("every retired word leads to a word that exists, and is no longer one itself") {
        val retired = vocabulary.retired.names
        check(retired.isNotEmpty()) { "art/retired.json was not read" }
        for (name in retired) {
            val successor = vocabulary.retired.successorOf(name)
            check(successor != null && vocabulary.word(successor) != null) { "'$name' leads nowhere" }
        }
        check(vocabulary.problems.isEmpty()) { "loading reported ${vocabulary.problems}" }
    }

    test("a retired word reads as its successor, by bare name or by id") {
        check(vocabulary.word("glimmering") === vocabulary.word("bright")) { "'glimmering' no longer reads as 'bright'" }
        check(vocabulary.word("agesandtheart:drowned") === vocabulary.word("flooded")) { "'agesandtheart:drowned' is not 'flooded'" }
        check(vocabulary.word("agesandtheart:restless") === vocabulary.word("frequent")) {
            "'agesandtheart:restless' no longer reads as 'frequent'"
        }
        check(vocabulary.word("agesandtheart:spawns") === vocabulary.word("inhabitants")) { "an aspect's former page is not its page" }
    }

    test("a name a live word answers to is that word, though a word of ours was retired from it") {
        check(vocabulary.word("drowned")?.id?.toString() == "minecraft:drowned") { "'drowned' is not the creature" }
    }

    test("a learned set is carried over, in place, without a repeat") {
        val learned = LearnedWords()
        learned.learn("floating".location())
        learned.learn("glimmering".location())
        learned.learn("bright".location())
        val changed = learned.migrate(vocabulary::successorIds)
        check(changed) { "a retired word was left in the learned set" }
        check(learned.words.toList() == listOf("floating".location(), "bright".location())) {
            "migration left ${learned.words}"
        }
    }

    test("a word retired into several reads as them, where it was written") {
        check(vocabulary.phraseOf("rising_north") == listOf("north", "rising")) { "'rising_north' reads as ${vocabulary.phraseOf("rising_north")}" }
        check(vocabulary.word("rising_north") == null) { "'rising_north' is still a word of its own" }
        check(vocabulary.stillNames("rising_north".location())) { "a page retired into a phrase reads as gone" }
        val reading = vocabulary.read(listOf("age", "rising_north", "sun"), SEED)
        val fate = reading?.pages?.get(1)?.fate
        check(fate == Fate.TakenBy(2)) { "the old page came to $fate, not taken by the sun" }
        val sun = vocabulary.resolve(listOf("age", "rising_north", "sun"), SEED)?.composition?.optionsFor(Aspect.SUN, 0)
        check(sun?.of(SkyBodies.RISING) == "north") { "'rising_north sun' rose ${sun?.of(SkyBodies.RISING)}" }
    }

    test("a learned word retired into several becomes each of them, in its place") {
        val learned = LearnedWords()
        learned.learn("floating".location())
        learned.learn("rising_north".location())
        learned.learn("rising".location())
        check(learned.migrate(vocabulary::successorIds)) { "the retired word was left in the learned set" }
        check(learned.words.toList() == listOf("floating".location(), "north".location(), "rising".location())) {
            "migration left ${learned.words}"
        }
    }

    test("a word, a structure page and a retired word still name something, and a word with no successor is gone") {
        check(vocabulary.stillNames("floating".location()) && vocabulary.stillNames("and".location())) {
            "a live word or a structure page reads as gone"
        }
        check(vocabulary.stillNames("glimmering".location())) { "a retired word with a successor reads as gone" }
        check(!vocabulary.stillNames(GONE)) { "a word nobody has reads as still there" }
        check(vocabulary.renamed["glimmering".location()] == vocabulary.word("bright")?.id) {
            "the client is not told 'glimmering' became 'bright': ${vocabulary.renamed}"
        }
    }

    test("a learned set forgets what is gone and keeps the rest, structure pages included") {
        val learned = LearnedWords()
        for (word in listOf("floating".location(), GONE, "and".location())) learned.learn(word)
        check(learned.forgetGone(vocabulary::stillNames)) { "nothing was forgotten" }
        check(learned.words.toList() == listOf("floating".location(), "and".location())) { "forgetting left ${learned.words}" }
    }
})

private val GONE = "a_word_nobody_has".location()
private const val SEED = 20261111L

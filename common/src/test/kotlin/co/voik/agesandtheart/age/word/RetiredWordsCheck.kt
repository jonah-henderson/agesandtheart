package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
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
    }

    test("a name a live word answers to is that word, though a word of ours was retired from it") {
        check(vocabulary.word("drowned")?.id?.toString() == "minecraft:drowned") { "'drowned' is not the creature" }
    }

    test("a learned set is carried over, in place, without a repeat") {
        val learned = LearnedWords()
        learned.learn("floating".location())
        learned.learn("glimmering".location())
        learned.learn("bright".location())
        val changed = learned.migrate(vocabulary::successorId)
        check(changed) { "a retired word was left in the learned set" }
        check(learned.words.toList() == listOf("floating".location(), "bright".location())) {
            "migration left ${learned.words}"
        }
    }
})

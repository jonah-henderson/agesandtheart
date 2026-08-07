package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.age.aspect.Aspect
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * Where a word reaches, against what its parameters say it must reach (§4.4).
 *
 * **A parameter is owned by exactly one aspect**, so a word that sets one and does not reach that aspect
 * does nothing with it — and nothing anywhere says so. That is the whole case for deriving: not to spare an
 * author a line, but to close a way of writing a word whose failure is invisible.
 *
 * What derivation cannot see is the rest of a word. `clear` sets `murk` in the air and is *also* a clear
 * sky; `arid` sets the climate axes and also wants dry rock. Neither reach is expressible as a parameter,
 * so the derived set is a floor under the declaration and never a replacement for it — which is what the
 * last check here holds down.
 */
@Tags("NEEDS_REGISTRIES")
class DerivedAspectsCheck : FunSpec({
    val vocabulary by lazy { Vocabulary.load(MinecraftRegistries.shippedData()) }

    fun aspectsOwning(parameter: String) = Aspect.entries.filter { it.ownsParameterNamed(parameter) }.toSet()

    /**
     * The premise everything else rests on. Two aspects sharing a parameter name would make "which aspect
     * owns this" unanswerable, and the derivation would start widening words at random.
     */
    test("no parameter name is owned by two aspects") {
        val everyParameter = vocabulary.words.flatMap { it.canSet.keys }.distinct()
        for (parameter in everyParameter) {
            val owners = aspectsOwning(parameter)
            check(owners.size <= 1) { "'$parameter' is owned by $owners, so no word setting it can be placed" }
        }
    }

    /**
     * **The static form has to agree with the corpus's**, because the derivation runs while the corpus is
     * still being built and cannot ask it. [Aspect.ownsParameterNamed] sees only authored presets and
     * dials, where [Vocabulary.turnsAKnob] also reaches an open aspect's data presets — the two agree
     * because every knob an open aspect steers is one of its dials, and this is what says so.
     */
    test("asking the aspect and asking the vocabulary give the same answer") {
        for (parameter in vocabulary.words.flatMap { it.canSet.keys }.distinct()) {
            val askingTheVocabulary = Aspect.entries.filter { vocabulary.turnsAKnob(it, parameter) }.toSet()
            check(aspectsOwning(parameter) == askingTheVocabulary) {
                "'$parameter': the aspect says ${aspectsOwning(parameter)}, the vocabulary says $askingTheVocabulary"
            }
        }
    }

    /** No shipped word steers a knob where it cannot reach — the inert setting this exists to prevent. */
    test("every word reaches every aspect it sets a parameter in") {
        for (word in vocabulary.words) {
            if (word.aspects.isEmpty()) continue
            for (parameter in word.canSet.keys) {
                val owner = aspectsOwning(parameter).singleOrNull() ?: continue
                check(owner in word.aspects) {
                    "${word.name} sets '$parameter', which only $owner honours, but reaches ${word.aspects}"
                }
            }
        }
    }

    /**
     * **Deriving only ever widens.** The three words that disagree with their own parameters — `clear`,
     * `arid`, `verdant` — are the reason: each declares more than it steers, and each is the vocabulary
     * being natural rather than an author being sloppy.
     */
    test("what a file declared survives the derivation") {
        val declared = setOf(Aspect.SKY, Aspect.ATMOSPHERE)
        val widened = Word.reaching(declared, mapOf("murk" to "0.1..0.4"))
        check(declared.all { it in widened }) { "deriving dropped a declared aspect: $widened" }
        check(Aspect.ATMOSPHERE in widened) { "deriving missed the aspect that owns the parameter: $widened" }

        val steersElsewhere = Word.reaching(setOf(Aspect.SKY), mapOf("temperature" to "0.5..1.0"))
        check(steersElsewhere == setOf(Aspect.SKY, Aspect.CLIMATE)) { "a parameter reached nothing: $steersElsewhere" }
    }

    /**
     * **Omitting the field never means "work it out"** — it still means anywhere.
     *
     * Three shipped words declare nothing and steer something, and all three are the case against: they
     * nudge one dial and query tags over every aspect there is. Deriving from parameters would shut
     * `beautiful` into the climate, and it would stop being beautiful anywhere else.
     */
    test("a word that declared nothing still means anywhere") {
        check(Word.reaching(emptySet(), mapOf("temperature" to "0.5..1.0")).isEmpty()) {
            "deriving narrowed a word that meant anywhere"
        }
        val steersAndSaysNothing = vocabulary.authoredWords
            .filter { it.aspects.isEmpty() && it.canSet.isNotEmpty() }
            .map { it.name }
        check(steersAndSaysNothing.containsAll(listOf("beautiful", "desolate", "rich"))) {
            "the words this rule exists for are gone, so the rule wants re-arguing: $steersAndSaysNothing"
        }
    }
})

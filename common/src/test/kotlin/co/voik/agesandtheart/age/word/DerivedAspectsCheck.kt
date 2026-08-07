package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.age.aspect.Aspect
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * Where a word reaches, against every concrete claim it makes (§4.4).
 *
 * A word acts on an aspect four ways and **three of them name the aspect outright**: a parameter is owned
 * by exactly one, a named preset belongs to whichever aspect's list holds it, and a weight is keyed by
 * aspect already. A claim landing where the word does not reach is never applied — and worse than
 * unapplied, it counts against the draw in the aspect the word *does* reach as a knob nothing honours. So
 * all three are unioned in, and the case for deriving is closing that hole rather than sparing a line.
 *
 * **The fourth is the tag query, and deriving from it is the spike's worst bug.** `stormy` means `gloomy`,
 * `gloomy` is also on `caverns`, so a sky word pinned the terrain and discarded `floating` in silence. A
 * query says what a word likes; it never says where it belongs.
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
     * **Deriving only ever widens.** The words that disagree with their own concrete claims — `clear`,
     * `arid`, `verdant` — each declare *more* than they claim, reaching further by tag query alone, and
     * each is the vocabulary being natural rather than an author being sloppy.
     */
    test("what a file declared survives the derivation") {
        val declared = setOf(Aspect.SKY, Aspect.ATMOSPHERE)
        val widened = Word.reaching(Tier.EXACT, declared, mapOf("murk" to "0.1..0.4"), named = null, weighted = emptySet())
        check(declared.all { it in widened }) { "deriving dropped a declared aspect: $widened" }
        check(Aspect.ATMOSPHERE in widened) { "deriving missed the aspect that owns the parameter: $widened" }

        val steersElsewhere =
            Word.reaching(Tier.EXACT, setOf(Aspect.SKY), mapOf("temperature" to "0.5..1.0"), null, emptySet())
        check(steersElsewhere == setOf(Aspect.SKY, Aspect.CLIMATE)) { "a parameter reached nothing: $steersElsewhere" }
    }

    /** A named preset places a word exactly, which is what makes the seventeen terrain words derivable. */
    test("naming a preset reaches the aspect whose preset it is") {
        val fromAName = Word.reaching(Tier.EXACT, setOf(Aspect.SKY), emptyMap(), named = "alps", weighted = emptySet())
        check(fromAName == setOf(Aspect.SKY, Aspect.TERRAIN)) { "a named preset reached nothing: $fromAName" }
    }

    /**
     * **An open aspect must not claim a name it was merely handed.** `Biome.named("alps")` succeeds — a
     * bare path is a valid identifier — so asking every aspect whether it knows `alps` would widen a
     * terrain word into the biomes, spawns, features and structures at once. Asking the authored list
     * cannot do that, and this is what says so.
     */
    test("an id-shaped name does not belong to every open aspect") {
        val claiming = Aspect.entries.filter { it.ownsPresetNamed("alps") }
        check(claiming == listOf(Aspect.TERRAIN)) { "'alps' was claimed by $claiming" }
    }

    /** A weight is keyed by aspect already, so it says where it applies and needs no deriving at all. */
    test("a weight reaches the aspect it is keyed under") {
        val fromAWeight =
            Word.reaching(Tier.EXACT, setOf(Aspect.CLIMATE), emptyMap(), null, weighted = setOf(Aspect.BIOMES))
        check(fromAWeight == setOf(Aspect.CLIMATE, Aspect.BIOMES)) { "a weight reached nothing: $fromAWeight" }
    }

    /**
     * **A narrowing word may never mean anywhere**, which is the `stormy` bug stated as an invariant: a
     * word that removes candidates and is aimed at nothing removes them everywhere, on the strength of a
     * tag it shares with content it was never about.
     *
     * Evocative words are exempt because tilting everywhere is what makes them evocative (§4.4) — they
     * take no freedom away, so being unaimed costs nothing.
     */
    test("nothing that narrows is left aimed at nothing") {
        val unaimed = vocabulary.authoredWords.filter { it.tier.narrows && it.aspects.isEmpty() }
        check(unaimed.isEmpty()) {
            "these narrow candidates in every aspect at once: ${unaimed.map { "${it.name} (${it.tier})" }}"
        }
    }

    /**
     * **Omitting the field never means "work it out"** — it still means anywhere.
     *
     * Three shipped words declare nothing and steer something, and all three are the case against: they
     * nudge one dial and query tags over every aspect there is. Deriving from parameters would shut
     * `beautiful` into the climate, and it would stop being beautiful anywhere else.
     */
    test("a word that declared nothing still means anywhere") {
        val claimingPlenty =
            Word.reaching(Tier.EVOCATIVE, emptySet(), mapOf("temperature" to "0.5..1.0"), "alps", setOf(Aspect.BIOMES))
        check(claimingPlenty.isEmpty()) { "deriving narrowed a word that meant anywhere: $claimingPlenty" }
        val steersAndSaysNothing = vocabulary.authoredWords
            .filter { it.aspects.isEmpty() && it.canSet.isNotEmpty() }
            .map { it.name }
        check(steersAndSaysNothing.containsAll(listOf("beautiful", "desolate", "rich"))) {
            "the words this rule exists for are gone, so the rule wants re-arguing: $steersAndSaysNothing"
        }
    }
})

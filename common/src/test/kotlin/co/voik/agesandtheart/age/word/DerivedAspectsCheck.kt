package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus.vocabulary
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.word.grammar.Grammar
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * Where a word reaches, against every concrete claim it makes (§4.4).
 *
 * A word acts on an aspect four ways and **three of them name the aspect outright**: a parameter is owned
 * by exactly one, a named preset belongs to whichever aspect's list holds it, and a weight is keyed by
 * aspect already. A claim landing where the word does not reach is never applied — and worse than
 * unapplied, it counts against the draw in the aspect the word *does* reach as a parameter nothing honours. So
 * all three are unioned in, and the case for deriving is closing that hole rather than sparing a line.
 *
 * **The fourth is the tag query, and deriving from it is the spike's worst bug.** `stormy` means `gloomy`,
 * `gloomy` is also on `caverns`, so a sky word pinned the terrain and discarded `floating` in silence. A
 * query says what a word likes; it never says where it belongs.
 */
@Tags(NEEDS_REGISTRIES)
class DerivedAspectsCheck : FunSpec({

    fun aspectsOwning(parameter: String) = Aspect.entries.filter { it.ownsParameterNamed(parameter) }.toSet()

    /**
     * **A parameter is a preset's or an aspect's, never both** — which is the bug this rule was written for, and
     * all that is left of it.
     *
     * It used to forbid two *aspects* sharing a name outright, on the grounds that "which aspect owns this"
     * would be unanswerable. It is perfectly answerable: [Word.reaching] filters and takes every owner, so
     * a word setting a shared parameter reaches both aspects and attachment picks between them — which is how
     * one `rising_east` means the same thing about a sun and about a moon.
     *
     * What is a real fault is a *preset* declaring a parameter its own aspect also declares, because then the
     * parameter exists twice for one thing and a word setting it is widened onto an aspect that merely contains
     * the preset. `sunsize` was owned by the sky through a stale preset declaration and by the sun through
     * its parameters, and that is the shape this still refuses.
     *
     * **Asked of one aspect at a time**, which the wording above always meant and the code only managed
     * while parameter names were unique. `size` is a landform's parameter and four other aspects' dial, and
     * that is one name doing its job in five places rather than one parameter existing twice.
     */
    test("a parameter belongs to a preset or to an aspect, never both — asked of one aspect at a time") {
        val everyParameter = vocabulary.words.flatMap { it.canSet.keys }.distinct()
        for (parameter in everyParameter) {
            val bothWays = Aspect.entries.filter { aspect ->
                val throughAPreset = aspect.authored.any { it.honoursParameterNamed(parameter) }
                val throughDials = aspect.parameters.any { it.name == parameter }
                throughAPreset && throughDials
            }
            check(bothWays.isEmpty()) {
                "'$parameter' is both a dial of $bothWays and a parameter of its presets, " +
                    "so it exists twice for one thing"
            }
        }
    }

    /**
     * **The static form has to agree with the corpus's**, because the derivation runs while the corpus is
     * still being built and cannot ask it. [Aspect.ownsParameterNamed] sees only authored presets and
     * parameters, where [Vocabulary.turnsAParameter] also reaches an open aspect's data presets — the two agree
     * because every parameter an open aspect steers is one of its parameters, and this is what says so.
     */
    test("asking the aspect and asking the vocabulary give the same answer") {
        for (parameter in vocabulary.words.flatMap { it.canSet.keys }.distinct()) {
            val askingTheVocabulary = Aspect.entries.filter { vocabulary.turnsAParameter(it, parameter) }.toSet()
            check(aspectsOwning(parameter) == askingTheVocabulary) {
                "'$parameter': the aspect says ${aspectsOwning(parameter)}, the vocabulary says $askingTheVocabulary"
            }
        }
    }

    /** No shipped word steers a parameter where it cannot reach — the inert setting this exists to prevent. */
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
     * **And a word must do something in every aspect it declares** — the other half of the same rule, and
     * the one that went missing.
     *
     * `starlit` and `starless` stayed on `sky` when the world model split the star field off it. The
     * derivation widened them into `stars`, so the setting still landed and the check above still passed;
     * but a clause *aimed* at `sky` confines the claim to `sky`, and `starlit sky` therefore read cleanly
     * and did nothing at all.
     *
     * Asked only of words whose whole content is what they set. A word that reaches by tag query means
     * something wherever those tags are carried, and no parameter can confirm or deny that — which holds
     * for an *offered* query too, so the aspects one names count as places the word speaks to.
     */
    test("a word that only sets parameters declares nothing but the aspects owning them") {
        fun constrainsPresets(word: Word) =
            word.chooses.isNotEmpty() || word.entryOf != null || word.excludes.isNotEmpty() ||
                word.restricts.values.any { tags -> tags.values.any { it > 0.0 } }
        val setsAndNothingElse = vocabulary.authoredWords
            .filter { !constrainsPresets(it) && it.biases.isEmpty() && it.canSet.isNotEmpty() }
        for (word in setsAndNothingElse) {
            // A **lean** names its aspect and means something there, exactly as a restriction does —
            // `inferno` leans the sea toward lava and turns no parameter of the sea at all.
            val owners = word.canSet.keys.flatMap(::aspectsOwning).toSet() + word.biases.keys
            val idle = word.aspects - owners
            check(idle.isEmpty()) {
                "${word.name} declares $idle, where it sets nothing and asks nothing — a clause aimed at " +
                    "${idle.first()} would read it and change nothing"
            }
        }
    }

    /**
     * **A word reaches where its claims point, and nowhere else.**
     *
     * There is no declaration to survive any more: a word said where it spoke *and* derived it, and the
     * declaration's only real job was aiming a bare `query`. Keying the query says the same thing once,
     * so what is left is the derivation alone.
     */
    test("a parameter puts the word in the aspect that owns it") {
        val murk = Word.reaching(mapOf("murk" to "0.1..0.4"), meant = emptySet(), weighted = emptySet())
        check(Aspect.WATERS in murk) { "deriving missed the aspect that owns the parameter: $murk" }

        val warmth = Word.reaching(mapOf("temperature" to "0.5..1.0"), emptySet(), emptySet())
        check(warmth == setOf(Aspect.CLIMATE)) { "a parameter reached nothing: $warmth" }

        // A keyed query is what a query is now, and it aims the word at what it asks of.
        val asked = Word.reaching(emptyMap(), emptySet(), setOf(Aspect.SPAWNS))
        check(asked == setOf(Aspect.SPAWNS)) { "a keyed query reached nothing: $asked" }
    }

    /**
     * A preset meant outright places a word exactly, which is what lets the seventeen landform pages be
     * minted from the landforms themselves.
     *
     * **The aspect comes from the key it is written under, not from a search.** `Biome.named("alps")`
     * succeeds — a bare path is a valid identifier — so a bare name offered to every open aspect would
     * widen a terrain word into the biomes, spawns, features and structures at once.
     */
    test("meaning a preset reaches the aspect it is meant in") {
        val fromAMeaning = Word.reaching(emptyMap(), meant = setOf(Aspect.TERRAIN), weighted = emptySet())
        check(fromAMeaning == setOf(Aspect.TERRAIN)) { "a meaning reached nothing: $fromAMeaning" }
    }

    /** A weight is keyed by aspect already, so it says where it applies and needs no deriving at all. */
    test("a weight reaches the aspect it is keyed under") {
        val fromAWeight = Word.reaching(emptyMap(), emptySet(), weighted = setOf(Aspect.BIOMES))
        check(fromAWeight == setOf(Aspect.BIOMES)) { "a weight reached nothing: $fromAWeight" }
    }

    /**
     * **A keyed query is the fourth channel, and the only way a tag ever says where a word belongs.**
     *
     * A flat query never can — `stormy` means `gloomy`, `gloomy` is also on `caverns` — but an author who
     * writes the tag *under an aspect* has said it outright, which is a different statement and a safe one.
     * `clear` is the word that wanted it: clear water is the `murk` dial and a clear sky is `bright` asked
     * only of the sky, so between them it declares no aspects at all.
     */
    test("a query asked of one aspect reaches it") {
        val clear = vocabulary.word("clear") ?: error("the corpus lost 'clear'")
        check(clear.aspects == setOf(Aspect.SKY, Aspect.WATERS)) {
            "'clear' derived ${clear.aspects} from a keyed query and a murk dial"
        }
        check(clear.restrictsIn(Aspect.SKY).containsKey("bright")) { "the restriction did not reach the sky" }
        check(!clear.restrictsIn(Aspect.SEA).containsKey("bright")) {
            "a restriction asked only of the sky went looking for something bright in the sea"
        }
    }

    /** A lean on the whole Age still falls wherever the tag is carried, which is what makes it evocative. */
    test("a lean on the whole Age is felt in every aspect") {
        val beautiful = vocabulary.word("beautiful") ?: error("the corpus lost 'beautiful'")
        check(beautiful.leansEverywhere.containsKey("#colourful")) { "'beautiful' stopped leaning colourful" }
        val felt = Aspect.entries.filter { aspect ->
            vocabulary.candidatesFor(aspect).any { beautiful.biasOn(it, vocabulary.tagsOf(it)) != 0.0 }
        }
        check(felt.size > 1) { "a lean on the whole Age was felt only in $felt" }
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
        // **A word that names a template is the exception, and the only one.** It narrows no candidates
        // anywhere: it replaces the world the book starts from, which is a different job from removing
        // answers within one, so having no aspect is what it *is* rather than a declaration left off.
        val unaimed = vocabulary.authoredWords
            .filter { it.tier.narrows && it.aspects.isEmpty() && it.template == null }
        check(unaimed.isEmpty()) {
            "these narrow candidates in every aspect at once: ${unaimed.map { "${it.name} (${it.tier})" }}"
        }
    }

    /**
     * **An evocative word still means anywhere**, and the tier is what says so.
     *
     * `beautiful` nudges the climate and asks tags of everything, and shutting it into the climate would
     * stop it being beautiful anywhere else. That used to be held by the derivation refusing to widen a
     * word that declared nothing; with the declaration gone it is held where it always belonged — a
     * constraint carries no aim at all unless its word narrows, so nothing consults the reach.
     */
    test("an evocative word carries no aim, whatever it reaches") {
        val warm = Word.reaching(mapOf("temperature" to "0.5..1.0"), setOf(Aspect.TERRAIN), setOf(Aspect.BIOMES))
        check(warm.isNotEmpty()) { "the derivation found nothing to check against" }

        val read = Grammar.read(vocabulary, listOf("beautiful", "age"))
        checkNotNull(read) { "'beautiful age' is not a book" }
        val laid = read.phrases.flatMap { it.modifiers }.filter { it.word.name == "beautiful" }
        check(laid.isNotEmpty()) { "'beautiful' was not laid at all" }
        check(laid.all { it.aimedAt.isEmpty() }) {
            "an evocative word was aimed at ${laid.map { it.aimedAt }}"
        }

        // And the words the rule exists for are still the shape it was written about.
        val tilting = vocabulary.authoredWords
            .filter { !it.tier.narrows && it.leansEverywhere.isNotEmpty() }
            .map { it.name }
        check(tilting.containsAll(listOf("beautiful", "desolate", "rich"))) {
            "the words this rule exists for are gone, so the rule wants re-arguing: $tilting"
        }
    }
})

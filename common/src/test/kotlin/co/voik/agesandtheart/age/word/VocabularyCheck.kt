package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.AgePreset
import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.AgeTemplate
import co.voik.agesandtheart.age.AgeWorld
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.aspect.Setting
import co.voik.agesandtheart.age.aspect.namesReferent
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * Reads the Art's vocabulary the way a server would and asks whether it is *sound content* — a different
 * question from whether the resolver works, which is [ResolverCheck]'s.
 *
 * It enforces §3.3's rule: **we only make words we can back up.** A word nothing in the world can satisfy
 * costs nothing, changes nothing and is reported as nothing, so the player gets a world their sentence did
 * not describe — and words are datapack data, so nothing else stands between a typo and that.
 *
 * Offline over the module's own `src/main/resources`, which also rehearses the game's loading path. It
 * needs the *built-in* registries because §8's derived vocabulary is read from them; biomes are datapack
 * content and would need a server.
 */
@Tags(NEEDS_REGISTRIES)
class VocabularyCheck : FunSpec({

    val vocabulary by lazy { Vocabulary.load(MinecraftRegistries.shippedData(), MinecraftRegistries.worldgen) }

    /**
     * Nothing failed to load.
     *
     * First because everything below is weaker than it looks if a file was quietly skipped: a word that never
     * loaded is trivially "backed", never contradicts anything, and passes every other check here.
     */
    test("every file was understood") {
        check(vocabulary.problems.isEmpty()) {
            "The vocabulary did not load cleanly:\n  ${vocabulary.problems.joinToString("\n  ")}"
        }
        check(vocabulary.words.isNotEmpty()) { "No words loaded at all — is the data directory where it was?" }
    }

    /**
     * Every word has something in the world that can satisfy it, in every aspect it claims to be about.
     * Two ways to fail, told apart: a word asking for a tag nothing carries, and a word about an aspect
     * whose presets do not carry its tag *at its own tier's strictness* — the subtler one, the tag and the
     * aspect both being real.
     *
     * **A tag only a bound registry tag can grant is exempt**, because this corpus has none bound: `ore`
     * is what `#minecraft:diamond_ores` says and nothing offline can know it. Asserting otherwise would be
     * asserting against a corpus the game never sees — `VocabularyOnServerCheck` holds that half, which is
     * the split `notes/the-tag-layer.md` §4 sets out.
     */
    test("every word is backed by the world") {
        val onlyAServerKnows = vocabulary.tagsOnlyAServerGrants
        for (word in vocabulary.words) {
            val unknownTags = word.wanted - vocabulary.carriedTags - onlyAServerKnows
            check(unknownTags.isEmpty()) {
                "'${word.name}' asks for ${unknownTags.joinToString(" ")}, which nothing in the world carries"
            }
            val aspects = Resolver.aspectsSpokenTo(vocabulary, word)
            // A word that names a template has a say in no aspect *and* does the largest thing a single
            // page can: it changes the world the book starts from. Every other word has to reach one.
            check(aspects.isNotEmpty() || word.template != null) {
                "'${word.name}' has a say in no aspect at all, so writing it would do nothing and cost nothing"
            }
            // A word that named its aspects must be satisfiable in **each** of them; an evocative word named
            // none, and having found purchase anywhere is what it promised.
            //
            // Read off the word rather than off `aspectsSpokenTo`, which answers what a word *costs* — one
            // aspect, for a narrowing word, however many it is at home in. Asking that here left every
            // declaration after the first unchecked, which was invisible while they were also unreachable.
            val declared = word.aspects.sortedBy { it.ordinal }
            for (aspect in declared) {
                // "Backed" means something different for a word that *steers* rather than *chooses* (§3.2):
                // it constrains no presets, so it has no carriers by construction and asking for one would
                // condemn every material word. What it needs instead is a preset that offers the knob.
                //
                // Asked **per aspect**, because a derived block word does both: it names a sea, where the
                // aspect's value simply *is* a block, and sets a material on the terrain and the dressing, which
                // hold nothing called `minecraft:copper_block`. Asking globally condemned every block in the
                // game for failing to be a terrain.
                // A word may *narrow* in one aspect and merely *steer* in another, and then having no carrier
                // here is no fault at all: `arid` narrows the terrain on `dry`/`barren` tags and bounds the
                // climate's axes with spans, which is two real jobs. What the check is actually for is a word
                // with *nothing* to do in an aspect it claims — so the question is whether it turns a knob this
                // aspect holds, not whether it happens to also carry a query.
                // Asked of the vocabulary rather than of the candidates, because an aspect with no
                // candidates can still hold knobs — a climate is nothing but its dials.
                // `canSet` rather than `sets`: whether a word steers an aspect is a question about what it
                // *means*, and a draw must not move it. Asked of the core alone, `scorching` — whose `murk`
                // is in its pool — looked like a word that only narrows the water, and the water has
                // nothing to narrow.
                val turnsAKnobHere = word.canSet.keys.any { vocabulary.turnsAKnob(aspect, it) }
                if (!word.constrainsPresetsIn(aspect) || turnsAKnobHere) {
                    // **Only the knobs this aspect holds.** One word carries a single `sets` map across
                    // every aspect it speaks to, and a derived block word now sets the rock's material and
                    // the skin's — so each aspect sees a key it has never heard of, and asking every aspect
                    // about every key condemns the whole block registry. That a key exists *somewhere* the
                    // word is about is asked once, after this loop, which is where a typo is caught.
                    fun knobsHere(parameter: String) = (
                        vocabulary.candidatesFor(aspect).flatMap { it.parameters } + aspect.dials
                        ).filter { it.name == parameter }
                    for (parameter in word.canSet.keys.filter { knobsHere(it).isNotEmpty() }) {
                        val offered = knobsHere(parameter)
                        // Declaring a knob and turning it are different things (`AspectPreset.honours`), and only
                        // the second makes a word mean anything. Continentalness and erosion shipped as climate
                        // axes that nothing could honour and were invisible in game for a whole session — this
                        // is the check that would have caught them before they were written.
                        val anythingTurnsIt = vocabulary.turnsAKnob(aspect, parameter)
                        check(anythingTurnsIt) {
                            "'${word.name}' sets ${aspect.key}.$parameter, which every ${aspect.key} declares and " +
                                "none acts on — so writing it would change nothing and say nothing"
                        }
                        // **This parameter's own value**, not every value the word carries: a word may set
                        // two knobs of one aspect — `sunless` bounds `daylight` with a span and picks
                        // `sunburn` by name — and asking each knob about the other's value condemns both.
                        val option = word.canSet.getValue(parameter)
                        // A value may offer **alternatives** the Age draws one of — `red|orange|yellow` —
                        // and every one of them has to be a value the knob takes. Asked of the whole
                        // string, the bar and all, an offer of three good colours read as one bad one.
                        val alternatives = option.split('|').map(String::trim).filter(String::isNotEmpty)
                        val unacceptable = alternatives.filterNot { one -> offered.any { it.accepts(one) } }
                        check(unacceptable.isEmpty()) {
                            "'${word.name}' sets ${aspect.key}.$parameter to " +
                                "'${unacceptable.joinToString("|")}', which it does not take"
                        }
                    }
                    continue
                }
                // A population is not seated, so it has no carriers to have: what backs a word there is
                // anything answering it *either way*, since a word about a population may be entirely
                // negative and still be about it.
                if (aspect.holds == Holds.WEIGHTED_SET) {
                    // Unless the only thing it asks for is a tag no offline corpus can carry — see above.
                    val onlyAServerCouldAnswer = word.wanted.isNotEmpty() &&
                        word.wanted.all(onlyAServerKnows::contains)
                    if (onlyAServerCouldAnswer) continue
                    check(vocabulary.answersIn(word, aspect)) {
                        "'${word.name}' is ${word.tier.key} about ${aspect.key}, and nothing there answers " +
                            "${word.query.keys.joinToString(" ")} at all"
                    }
                    continue
                }
                val carriers = vocabulary.carriersOf(word, aspect)
                check(carriers.isNotEmpty()) {
                    "'${word.name}' is ${word.tier.key} about ${aspect.key}, but no ${aspect.key} carries " +
                        "${word.wanted.joinToString(" ")} strongly enough (needs ${word.tier.threshold})"
                }
            }
            // And every knob it turns exists in *some* aspect it is about — the typo guard the per-aspect
            // loop above stopped being once one word's knobs could span aspects.
            val couldBeAimedAnywhere = word.aspects.isEmpty()
            for (parameter in word.sets.keys) {
                check(couldBeAimedAnywhere || word.aspects.any { vocabulary.turnsAKnob(it, parameter) }) {
                    "'${word.name}' sets '$parameter', which nothing it is about turns"
                }
            }
        }
    }

    /**
     * Every word that narrows says which aspects it narrows — guarded at the content layer, where a
     * forgotten line of JSON reintroduces it: an unscoped precise word gets a say in every aspect its tags
     * touch, so `stormy` pins the terrain to caverns and throws `floating` away in silence.
     */
    test("every narrowing word says what it is about") {
        for (word in vocabulary.words.filter { it.tier.narrows && it.template == null }) {
            check(word.aspects.isNotEmpty()) {
                "'${word.name}' is ${word.tier.key} but names no aspect, so it would narrow every aspect its tags " +
                    "reach — which is how a word about the sky ends up choosing the ground"
            }
        }
    }

    /**
     * **No authored word is a synonym for a derived one.**
     *
     * §8.1 already mints a word for every block, biome and structure set, so an authored word that resolves
     * to exactly one registry id says nothing the derived word does not — and it is worse than redundant.
     * Two pages that produce the same Age tell a writer there is a distinction worth spending ink on when
     * there is none: somebody laying `slate` rather than `deepslate` is owed something different, and got
     * the same block.
     *
     * `basalt` was the sharper case, because authored words win every collision: it set *blackstone* while
     * wearing the name of the block it was displacing, so `basalt` in a book gave you something else.
     *
     * An authored word may still reach a referent — but only while doing something the derived word cannot,
     * which means carrying a query of its own as well.
     */
    test("no authored word is a synonym for a derived one") {
        for (word in vocabulary.authoredWords) {
            val referents = (listOfNotNull(word.names) + word.sets.values).filter(::namesReferent)
            val saysNothingElse = word.query.isEmpty()
            check(referents.isEmpty() || !saysNothingElse) {
                "'${word.name}' resolves to ${referents.joinToString()} and nothing else, which is what the " +
                    "derived word already does — so laying it buys a writer nothing over laying that"
            }
        }
    }

    /** Every aspect has at least one word about it, or part of the world is unwritable. */
    test("every slot has words") {
        for (aspect in Aspect.entries) {
            val about = vocabulary.words.count { aspect in it.aspects }
            check(about > 0) { "No word is about the ${aspect.key} aspect, so nothing a writer says can steer it" }
        }
    }

    /**
     * Every preset can be reached by some word. One no sentence can ask for is content nobody can use: it
     * cannot be written for, and since [Vocabulary.askableIn] keeps it out of the draw it cannot arrive by
     * chance either, so nothing in the game would ever produce it.
     */
    test("every preset can be asked for") {
        for (aspect in Aspect.entries) {
            // An aspect with a single candidate needs no word to ask for it, and demanding one asks the wrong
            // question. This check exists because a preset nobody can name "will still turn up when the seed
            // draws an unconstrained aspect" — but where there is nothing to draw *between*, no chance is
            // involved and the preset arrives by construction. Climate is the case: one preset, and all of its
            // writing happens in ranged parameters (see [co.voik.agesandtheart.age.aspect.Climate]).
            if (vocabulary.candidatesFor(aspect).size <= 1) continue
            // The curated pool, not the registry: a derived word reaches every referent by construction, so
            // the only presets that can go unreachable are the ones somebody chose to curate (design §8.2).
            for (preset in vocabulary.candidatesFor(aspect)) {
                // A preset that says it is unaskable is exempt from needing a word — but not from scrutiny: the
                // pinned-preset check below insists it really is pinned somewhere, so a careless `false` still
                // fails.
                if (!preset.askableInASentence) continue
                // A referent is reachable by name by construction — §8.1 mints a word per registry entry —
                // so demanding one here asks the wrong question, and asks it of a corpus that cannot answer:
                // biomes are datapack content, so their words exist only once a server has loaded.
                if (namesReferent(preset.key)) continue
                val reachable = vocabulary.words.any { word ->
                    aspect in Resolver.aspectsSpokenTo(vocabulary, word) && word.tier.narrows &&
                        preset in vocabulary.carriersOf(word, aspect)
                }
                check(reachable) {
                    "No word can ask for ${aspect.key}=${preset.key}, so it can only ever arrive by chance. " +
                        "Its tags are ${vocabulary.tagsOf(preset)}. If that is deliberate — a preset only a pinned " +
                        "recipe names — say so with `askableInASentence = false` rather than adding a word for it."
                }
            }
        }
    }

    /**
     * Every preset that opted out of being askable is actually **pinned by a recipe** — the other half of
     * the exemption above, and what makes it safe. An omission and an intention look identical from
     * outside, so `askableInASentence = false` buys an exemption from one check and immediately owes this
     * one. Neither askable nor pinned is dead content that can still be drawn.
     */
    test("every unaskable preset is pinned by a recipe") {
        // Two ways to reach one deliberately: a pinned recipe, or a **template**, which is how
        // `landmass=vanilla` arrives — an Age whose writer named no landform gets the rock its world
        // came with.
        val pinned = AgePreset.entries
            .mapNotNull { preset -> (AgeRecipe.worldFor(preset) as? AgeWorld.Composed)?.composition }
            .plus(AgeTemplate.entries.map { it.world() })
            .flatMap { composition -> composition.presets }
            .toSet()
        for (aspect in Aspect.entries) {
            for (preset in vocabulary.candidatesFor(aspect).filterNot { it.askableInASentence }) {
                check(preset in pinned) {
                    "${aspect.key}=${preset.key} says it is unaskable, but no pinned recipe names it either — so " +
                        "nothing can reach it deliberately. Pin it in `AgeRecipe.worldFor`, put it in a template, " +
                        "or give it a word."
                }
            }
        }
    }

    /**
     * Every antonym pair could actually fire.
     *
     * A pair naming a tag nothing carries is a dead row: harmless, but it looks like coverage where there is
     * none, and the table's whole job is explaining tensions the data found.
     */
    test("every antonym could fire") {
        for (antonym in vocabulary.antonyms) {
            for (tag in listOf(antonym.first, antonym.second)) {
                check(tag in vocabulary.carriedTags) {
                    "The antonym '${antonym.first} vs ${antonym.second}' names '$tag', which nothing carries"
                }
            }
            check(vocabulary.words.any { antonym.first in it.wanted }) {
                "No word asks for '${antonym.first}', so the pair '${antonym.first} vs ${antonym.second}' can never fire"
            }
            check(vocabulary.words.any { antonym.second in it.wanted }) {
                "No word asks for '${antonym.second}', so the pair '${antonym.first} vs ${antonym.second}' can never fire"
            }
        }
    }

    /**
     * §8.2's promise, asserted rather than trusted: **vagueness draws only from the curated pool.** It
     * holds structurally and so should never fire — which is why it is worth having, a structural
     * guarantee being one refactor from a convention, and the failure being quiet. "A beautiful world"
     * that draws a sea of somebody's radioactive sludge is the promise broken.
     */
    test("vagueness cannot reach derived content") {
        val curated = Aspect.entries.flatMap { aspect -> vocabulary.candidatesFor(aspect).map { it.key } }.toSet()
        val derived = vocabulary.words.filter { it.names != null }
        check(derived.isNotEmpty()) {
            "No derived words at all — the pack has fluids, so this means derivation is not running"
        }
        for (word in derived) {
            for (aspect in word.aspects) {
                val reachable = vocabulary.words.any { vague ->
                    !vague.tier.narrows && word.names in vocabulary.carriersOf(vague, aspect).map { it.key }
                }
                check(!reachable || word.names in curated) {
                    "'${word.name}' is derived content a vague word can reach in ${aspect.key}, and it was never " +
                        "curated — which is §8.2's promise broken, and invisible from the outside"
                }
            }
        }
    }

    /**
     * **The burning air belongs to the inferno and to nothing else** (design §5.2.2).
     *
     * Embers are the largest warning a player gets — *if the air is burning, prepare carefully* — and a
     * signal that also appears in Ages where nothing is burning is not a signal. Exclusivity is by
     * construction rather than by a rule, since a writer can only write what some word offers, so this is
     * the fence: it stops a later corpus edit quietly diluting the warning, which is exactly the kind of
     * change nobody would notice was a change.
     *
     * Embers specifically are worth the fuss because they are measurably the loudest thing the air can do —
     * `Motes` thins them sixteenfold on their own, after a walk found a lava spark at the ordinary rate
     * reads as being on fire rather than as weather.
     */
    test("only the inferno may hang burning air") {
        val reserved = setOf("embers", "flames")
        fun offers(word: Word) = (word.sets.entries + word.pool.entries)
            .filter { it.key == "motes" }
            .flatMap { it.value.split(POOL_ALTERNATIVES) }
            .filter { it in reserved }

        val leaks = vocabulary.words
            .filterNot { it.name == INFERNO }
            .mapNotNull { word -> offers(word).takeIf { it.isNotEmpty() }?.let { word.name to it } }

        check(leaks.isEmpty()) {
            "burning air is the inferno's warning and these words dilute it: " +
                leaks.joinToString { "${it.first} offers ${it.second}" }
        }
        val inferno = vocabulary.words.firstOrNull { it.name == INFERNO }
        check(inferno != null && offers(inferno).isNotEmpty()) { "the inferno hangs no burning air of its own" }
    }

    /**
     * **What a phenomenon demands of the rest of the world must be a demand, not a limit** (Jonah,
     * 2026-08-08).
     *
     * A [co.voik.agesandtheart.age.aspect.Setting.Bound] yields silently to anything outside it, so an
     * inferno written with `frozen` would have cost nothing at all — and a writer who asks for a frozen
     * world that burns has written a contradiction and should pay for it, exactly as `scorching frozen`
     * already does. A demand fractures against one it cannot meet, and the fracture is the charge.
     *
     * **Instability cannot compound through this**, which is why the rule is safe to be this strict: a
     * manifestation is bought with the index *after* the index is settled, so only what is written can
     * charge. An inferno the Art inflicted on a broken Age can never make it more broken.
     */
    test("a phenomenon's climate is demanded rather than merely preferred") {
        val inferno = vocabulary.words.firstOrNull { it.name == INFERNO }
        check(inferno != null) { "there is no inferno in the corpus" }
        for (axis in listOf("temperature", "rainfall")) {
            val said = inferno.sets[axis]
            check(said != null) { "the inferno says nothing about $axis, so a frozen one costs nothing" }
            check(Setting.read(said) is Setting.Fixed) {
                "the inferno's $axis is '$said', which yields silently — a written contradiction would be free"
            }
        }
    }
})

/** How a pool spells its alternatives. */
private const val POOL_ALTERNATIVES = '|'

private const val INFERNO = "inferno"

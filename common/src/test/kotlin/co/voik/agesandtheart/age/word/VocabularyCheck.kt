package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.AgePreset
import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.AgeTemplate
import co.voik.agesandtheart.age.AgeWorld
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Bars
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.aspect.RANGED_PARAMETERS
import co.voik.agesandtheart.age.aspect.Setting
import co.voik.agesandtheart.age.aspect.namesARegistryEntry
import co.voik.agesandtheart.age.aspect.ownParameters
import co.voik.agesandtheart.age.language.DerivedWords
import co.voik.agesandtheart.age.language.Word
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.preview.authoring.choiceIn
import co.voik.agesandtheart.preview.authoring.wantedTags
import co.voik.agesandtheart.preview.authoring.tagsSpokenOf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * Reads the Art's vocabulary the way a server would and asks whether it is *sound content* — a different
 * question from whether resolution works, which is `ResolutionCheck`'s.
 *
 * It enforces §3.3's rule: **we only make words we can back up.** A meaning nothing in the world can satisfy
 * costs ink and changes nothing, and words are datapack data, so nothing else stands between a typo and that.
 *
 * Offline over the module's own `src/main/resources`. A tag only a bound registry tag can grant (`ore`) is exempt
 * here and held by `VocabularyOnServerCheck` (`notes/the-tag-layer.md` §4).
 */
@Tags(NEEDS_REGISTRIES)
class VocabularyCheck : FunSpec({

    val vocabulary by lazy { Vocabulary.load(MinecraftRegistries.shippedData(), MinecraftRegistries.worldgen) }

    /** Everything below is weaker than it looks if a file was quietly skipped, so this goes first. */
    test("every file was understood") {
        check(vocabulary.problems.isEmpty()) {
            "The vocabulary did not load cleanly:\n  ${vocabulary.problems.joinToString("\n  ")}"
        }
        check(vocabulary.words.isNotEmpty()) { "No words loaded at all — is the data directory where it was?" }
    }

    /** Only an aspect's own page may mean nothing: it takes what its part of the world understands. */
    test("every word means something") {
        fun isAnAspectPage(word: Word) = Aspect.byPage(word.name) != null
        val saysNothing = vocabulary.words.filter { word ->
            (word.meanings.isEmpty() && !isAnAspectPage(word)) || word.meanings.any { it.effects.isEmpty() }
        }
        check(saysNothing.isEmpty()) { "these have a meaning that says nothing: ${saysNothing.map { it.name }}" }
    }

    test("every tag a word speaks of is carried by something") {
        val known = vocabulary.carriedTags + vocabulary.tagsOnlyAServerGrants
        for (word in vocabulary.words) {
            val unknown = word.tagsSpokenOf - known
            check(unknown.isEmpty()) { "'${word.name}' speaks of ${unknown.joinToString(" ")}, which nothing carries" }
        }
    }

    /**
     * **A narrowing is answered where it narrows.** The tag and the aspect can both be real and still no member of
     * that aspect carry the tag up to the bar, which is the subtler way a meaning lands on nothing.
     *
     * Two restrictions do another job and are exempt. One beside a parameter the word turns in the same part steers
     * rather than chooses. And one wanting a tag of an opposed pair says what the word *means* in tag space, which is
     * how incoherence finds it: `arid` keeps `#barren` in the climate, where nothing carries it, so that
     * `verdant arid` reads as lush against barren rather than as two spans that miss.
     */
    test("every restriction is met by some member of its part of the world") {
        val onlyAServerKnows = vocabulary.tagsOnlyAServerGrants
        fun isOpposable(tag: String) = vocabulary.opposedTags.any { it.first == tag || it.second == tag }
        for (word in vocabulary.words) {
            val admitted = word.effects.filterIsInstance<Effect.Admits>()
            val turned = word.effects.mapNotNull { (it as? Effect.Sets)?.parameter ?: (it as? Effect.Bends)?.parameter }
            for (restricts in word.effects.filterIsInstance<Effect.Restricts>()) {
                val wanted = restricts.bars.filterValues(Bars::isAtLeast).keys
                val onlyAServerCouldAnswer = wanted.isNotEmpty() && wanted.all(onlyAServerKnows::contains)
                val aspect = restricts.aspect
                val steersHere = turned.any { it.landsOn(aspect) && vocabulary.turnsAParameter(aspect, it.name) }
                if (onlyAServerCouldAnswer || steersHere || wanted.any(::isOpposable)) continue
                val admittedHere = admitted.filter { it.aspect == aspect }.flatMap { it.members }.mapNotNull(aspect::presetFor)
                val pool = vocabulary.availableToBroadWordsIn(aspect) + admittedHere
                val meeting = pool.filter { Bars.cleared(vocabulary.tagsOf(it), restricts.bars) }
                check(meeting.isNotEmpty()) {
                    "'${word.name}' keeps ${restricts.bars.entries.joinToString { (tag, bar) -> "#$tag ${Bars.spell(bar)}" }} " +
                        "in the ${aspect.page}, and nothing there meets it"
                }
            }
        }
    }

    /**
     * **A parameter a word sets is one something acts on, to a value it takes.** Declaring a parameter and turning it
     * are different things (`Taggable.honours`), and only the second makes a word mean anything.
     */
    test("every parameter a word sets is turned, to a value it takes") {
        fun parametersCalled(name: String, aspect: Aspect) =
            (vocabulary.candidatesFor(aspect).flatMap { it.ownParameters } + aspect.parameters).filter { it.name == name }
        for (word in vocabulary.words) {
            val sets = word.effects.filterIsInstance<Effect.Sets>().map { it.parameter to it.value } +
                word.effects.filterIsInstance<Effect.Bends>().map { it.parameter to null }
            for ((parameter, value) in sets) {
                val landsOn = Aspect.entries.filter(parameter::landsOn)
                check(landsOn.isNotEmpty()) {
                    "'${word.name}' sets ${parameter.spelled}, and no part of the world is called that"
                }
                val turnedOn = landsOn.filter { vocabulary.turnsAParameter(it, parameter.name) }
                check(turnedOn.isNotEmpty()) {
                    "'${word.name}' sets ${parameter.spelled}, which is declared and acted on by nothing"
                }
                if (value == null) continue
                val alternatives = value.split(POOL_ALTERNATIVES).map(String::trim).filter(String::isNotEmpty)
                fun isTaken(one: String) = turnedOn.any { aspect -> parametersCalled(parameter.name, aspect).any { it.acceptsFromAWord(one) } }
                val unacceptable = alternatives.filterNot(::isTaken)
                check(unacceptable.isEmpty()) {
                    "'${word.name}' sets ${parameter.spelled} to '${unacceptable.joinToString("|")}', which it does not take"
                }
            }
        }
    }

    /**
     * An aspect whose members are designs of ours holds what its `authored` list holds; one whose members are registry
     * entries holds any namespaced id, because `presetFor` parses rather than looks up.
     */
    test("a word names members the part of the world could hold") {
        for (word in vocabulary.words) {
            val named = word.effects.flatMap { effect ->
                when (effect) {
                    is Effect.Chooses -> listOf(effect.aspect to effect.member)
                    is Effect.Admits -> effect.members.map { effect.aspect to it }
                    else -> emptyList()
                }
            }
            for ((aspect, key) in named) {
                val oneOfOurDesigns = aspect.ownsPresetNamed(key)
                val anEntryOfItsRegistry = aspect.presetsAreEntriesOf != null && namesARegistryEntry(key)
                check(oneOfOurDesigns || anEntryOfItsRegistry) {
                    "'${word.name}' names '$key' in the ${aspect.page}, which holds neither — a design of ours is a " +
                        "bare key that aspect lists, and a registry entry is a namespaced id"
                }
            }
        }
    }

    /**
     * **No authored word is a synonym for a derived one.** §8.1 mints a word for every block, biome and structure set,
     * so an authored word that only names registry entries tells a writer there is a distinction worth ink where
     * there is none.
     */
    test("no authored word is a synonym for a derived one") {
        fun namesAReferent(effect: Effect) = when (effect) {
            is Effect.Chooses -> namesARegistryEntry(effect.member)
            is Effect.Sets -> namesARegistryEntry(effect.value)
            else -> false
        }
        for (word in vocabulary.authoredWords) {
            val referents = word.effects.filter(::namesAReferent)
            val saysNothingElse = word.effects.all(::namesAReferent)
            check(referents.isEmpty() || !saysNothingElse) {
                "'${word.name}' names $referents and nothing else, which is what the derived word already does"
            }
        }
    }

    /** An authored word replaces a derived one of the same id, so it must make every choice the minted page made. */
    test("no authored word hides the page minted for a preset") {
        val authoredById = vocabulary.authoredWords.associateBy { it.id }
        for (minted in DerivedWords.designs()) {
            val authored = authoredById[minted.id] ?: continue
            val lost = minted.effects.filterIsInstance<Effect.Chooses>() - authored.effects.filterIsInstance<Effect.Chooses>().toSet()
            check(lost.isEmpty()) {
                "the authored '${authored.name}' hides the page minted for $lost — give it that choice too, or name " +
                    "the preset something else"
            }
        }
    }

    /** Every aspect has a word that lands on it, or part of the world is unwritable. */
    test("every part of the world has words") {
        val reached = vocabulary.words.flatMap(vocabulary.lexicon::placesFor).toSet()
        val unwritable = Aspect.entries - reached
        check(unwritable.isEmpty()) { "no word lands on ${unwritable.map { it.page }}" }
    }

    /**
     * Every preset can be asked for. One no sentence can ask for can only arrive by chance, or never, if it is kept
     * from broad words. A preset only a pinned recipe names says so with `availableToBroadWords = false`, and owes
     * the check after this one.
     */
    test("every preset can be asked for") {
        for (aspect in Aspect.entries) {
            // Where there is nothing to draw between, the one candidate arrives by construction.
            if (vocabulary.candidatesFor(aspect).size <= 1) continue
            val restrictions = vocabulary.words.flatMap { it.effects }.filterIsInstance<Effect.Restricts>()
                .filter { it.aspect == aspect && it.bars.values.any(Bars::isAtLeast) }
            for (preset in vocabulary.candidatesFor(aspect)) {
                if (!preset.availableToBroadWords) continue
                // A referent is reachable by name by construction, and biome words exist only on a server.
                if (namesARegistryEntry(preset.key)) continue
                // A phenomenon instability inflicts is reached by instability until a word names it.
                if ((preset as? Phenomenon)?.inflictedBy != null && !vocabulary.isAvailableToBroadWords(preset)) continue
                val chosen = vocabulary.words.any { it.choiceIn(aspect)?.key == preset.key }
                val tags = vocabulary.tagsOf(preset)
                val narrowedTo = restrictions.any { Bars.cleared(tags, it.bars) }
                check(chosen || narrowedTo) {
                    "No word can ask for ${aspect.key}=${preset.key}, so it can only ever arrive by chance. " +
                        "Its tags are $tags. If that is deliberate — a preset only a pinned recipe names — say so " +
                        "with `availableToBroadWords = false` rather than adding a word for it."
                }
            }
        }
    }

    /**
     * Every preset kept from broad words is **pinned by a recipe** or a template — the other half of the exemption
     * above. An omission and an intention look identical from outside.
     */
    test("every preset kept from broad words is pinned by a recipe") {
        val pinned = AgePreset.entries
            .mapNotNull { preset -> (AgeRecipe.worldFor(preset) as? AgeWorld.Composed)?.composition }
            .plus(AgeTemplate.entries.map { it.world() })
            .flatMap { composition -> composition.presets }
            .toSet()
        for (aspect in Aspect.entries) {
            for (preset in vocabulary.candidatesFor(aspect).filterNot { it.availableToBroadWords }) {
                check(preset in pinned) {
                    "${aspect.key}=${preset.key} is kept from broad words, but no pinned recipe names it either — so " +
                        "nothing can reach it deliberately. Pin it in `AgeRecipe.worldFor`, put it in a template, " +
                        "or give it a word."
                }
            }
        }
    }

    /**
     * Every pair of opposed tags could fire: both tags carried, and a word wanting each. A pair nobody can write
     * charges nothing and looks like coverage.
     */
    test("every pair of opposed tags could fire") {
        for (pair in vocabulary.opposedTags) {
            for (tag in listOf(pair.first, pair.second)) {
                check(tag in vocabulary.carriedTags) {
                    "The opposed pair '${pair.first} vs ${pair.second}' names '$tag', which nothing carries"
                }
                check(vocabulary.words.any { tag in it.wantedTags }) {
                    "No word asks for '$tag', so the pair '${pair.first} vs ${pair.second}' can never fire"
                }
            }
        }
    }

    /**
     * §8.2's promise, asserted rather than trusted: **vagueness draws only from the curated pool.** A broad meaning's
     * pool is `availableToBroadWordsIn`, so that has to sit inside what was curated; derived content arrives only by
     * being named.
     */
    test("vagueness cannot reach derived content") {
        check(vocabulary.derivedWords.isNotEmpty()) {
            "No derived words at all — the pack has fluids, so this means derivation is not running"
        }
        for (aspect in Aspect.entries) {
            val curated = vocabulary.candidatesFor(aspect).map { it.key }.toSet()
            val uncurated = vocabulary.availableToBroadWordsIn(aspect).map { it.key }.filterNot(curated::contains)
            check(uncurated.isEmpty()) {
                "a vague word can reach ${uncurated.take(5)} in the ${aspect.page}, which was never curated"
            }
        }
    }

    /**
     * **The burning air belongs to the inferno and to nothing else** (design §5.2.2). A signal that also appears where
     * nothing is burning is not a signal, and a corpus edit diluting it is the kind nobody would notice.
     */
    test("only the inferno may hang burning air") {
        val reserved = setOf("embers", "flames")
        // The meanings a word never lands without, as the warning was always held: `savage` offers embers by chance.
        fun offers(word: Word) = word.meanings.filter { it.mustAlwaysApply }.flatMap { it.effects }.filterIsInstance<Effect.Sets>()
            .filter { it.parameter.name == MOTES }
            .flatMap { it.value.split(POOL_ALTERNATIVES) }
            .filter { it in reserved }

        val leaks = vocabulary.words
            .filterNot { it.name == INFERNO }
            .mapNotNull { word -> offers(word).takeIf { it.isNotEmpty() }?.let { word.name to it } }
        check(leaks.isEmpty()) {
            "burning air is the inferno's warning and these words dilute it: " +
                leaks.joinToString { "${it.first} offers ${it.second}" }
        }
        val inferno = vocabulary.word(INFERNO)
        check(inferno != null && offers(inferno).isNotEmpty()) { "the inferno hangs no burning air of its own" }
    }

    /**
     * **What a phenomenon asks of the rest of the world is a fixed value, not a limit**, so an inferno written with
     * `frozen` is a contradiction that is charged rather than a bound that yields in silence.
     */
    test("a phenomenon's climate is fixed rather than merely bounded") {
        val inferno = vocabulary.word(INFERNO)
        check(inferno != null) { "there is no inferno in the corpus" }
        val settings = inferno.effects.filterIsInstance<Effect.Sets>()
        for (axis in listOf("temperature", "weather.frequency")) {
            val said = settings.firstOrNull { it.parameter.spelled == axis }?.value
            check(said != null) { "the inferno says nothing about $axis, so a frozen one costs nothing" }
            check(Setting.read(said) is Setting.Fixed) {
                "the inferno's $axis is '$said', which yields silently — a written contradiction would be free"
            }
        }
    }

    /**
     * **A block word chooses nothing but itself.** An id does not say which registry it belongs to, so a block taken
     * for a structure set or a biome of the same shape was a page read, billed and dropped.
     */
    test("a block word chooses itself and nothing else") {
        val blocks = vocabulary.derivedWords.filter { it.block != null }
        check(blocks.isNotEmpty()) { "no derived block words at all" }
        fun choosesSomethingItIsNot(block: Word) =
            block.effects.filterIsInstance<Effect.Chooses>().any { it.member != block.id.toString() }
        val wrong = blocks.filter(::choosesSomethingItIsNot)
        check(wrong.isEmpty()) { "${wrong.size} block words choose something else: ${wrong.take(5).map { it.name }}" }
        val ice = vocabulary.word("ice")
        check(ice?.choiceIn(Aspect.STRUCTURES) == null) { "'ice' means a structure set" }
    }

    /** A sea nobody narrowed is the readiest one, so water must be readier than every other sea, alone. */
    test("water is the readiest sea, and alone") {
        val seas = vocabulary.availableToBroadWordsIn(Aspect.SEA)
        val readiest = seas.maxOf(vocabulary::readinessOf)
        val tied = seas.filter { vocabulary.readinessOf(it) == readiest }.map { it.key }
        check(tied == listOf("minecraft:water")) { "the readiest seas are $tied" }
    }

    /** **A bend moves a range**, so one naming a parameter with no range is a page charged for and doing nothing. */
    test("a bend has a range") {
        val bendingNothing = vocabulary.authoredWords.mapNotNull { word ->
            val unranged = word.effects.filterIsInstance<Effect.Bends>().map { it.parameter.name }.filterNot(RANGED_PARAMETERS::contains)
            unranged.takeIf { it.isNotEmpty() }?.let { "${word.name} (${it.joinToString()})" }
        }
        check(bendingNothing.isEmpty()) { "these bend what has no range: $bendingNothing" }
    }

    /**
     * **A preset has one page that means it**, so a second word choosing one is a synonym for a page that exists. An
     * aspect's page announcing its own phenomenon is not counted: `rainbows` asks for a bow without aiming at one.
     */
    test("no two pages choose the same preset") {
        val byPreset = vocabulary.words
            .filter { Aspect.byPage(it.name) == null }
            .flatMap { word -> word.effects.filterIsInstance<Effect.Chooses>().map { (it.aspect to it.member) to word.name } }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.distinct() }
            .filterValues { it.size > 1 }
        check(byPreset.isEmpty()) {
            byPreset.entries.joinToString("; ") { (where, words) ->
                "${where.first.page}'s '${where.second}' is chosen by ${words.sorted().joinToString(" ")}"
            }
        }
    }

    /** The landform pages are minted from the landforms (`DerivedWords.designs`), so a gap here closes itself. */
    test("every landform a writer can reach for has a page that means it") {
        val unsayable = Aspect.TERRAIN.authored
            .filter { it.availableToBroadWords }
            .filter { preset -> vocabulary.words.none { it.choiceIn(Aspect.TERRAIN)?.key == preset.key } }
        check(unsayable.isEmpty()) { "no page means ${unsayable.map { it.key }}" }
    }
})

/** How a value spells its alternatives. */
private const val POOL_ALTERNATIVES = '|'

private const val INFERNO = "inferno"
private const val MOTES = "motes"

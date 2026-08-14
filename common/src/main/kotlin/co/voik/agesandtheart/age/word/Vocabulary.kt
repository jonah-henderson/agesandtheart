package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.Constants
import com.google.gson.JsonParser
import co.voik.agesandtheart.age.Register
import co.voik.agesandtheart.location
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.AspectPreset
import co.voik.agesandtheart.age.aspect.Setting
import co.voik.agesandtheart.age.aspect.Spawning
import co.voik.agesandtheart.age.word.generation.GenerationGrammars
import co.voik.agesandtheart.age.word.grammar.GrammarWord
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.HolderLookup
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.packs.resources.Resource
import net.minecraft.server.packs.resources.ResourceManager

/**
 * Two tags that mean opposite things — the authored table of them (§3.3).
 *
 * **Not the contradiction detector.** What breaks an Age is an empty intersection, which [Resolver] finds
 * in the tag data itself: `floating` and `flat` are opposites in no dictionary, yet no terrain is both.
 * The table **explains** a tension and prices it where it knows the pair, so gaps in it cost clarity
 * rather than correctness — which is what makes it affordable to author by hand.
 */
data class Antonym(val first: String, val second: String, val severity: Int) {
    companion object {
        /** Tension the world absorbed is charged gently: a remark, not an accusation. */
        const val ORDINARY_SEVERITY = 1

        val CODEC: Codec<Antonym> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.STRING.fieldOf("first").forGetter(Antonym::first),
                Codec.STRING.fieldOf("second").forGetter(Antonym::second),
                Codec.INT.optionalFieldOf("severity", ORDINARY_SEVERITY).forGetter(Antonym::severity),
            ).apply(instance, ::Antonym)
        }
    }
}

/**
 * Why two words cannot both stand, and what they disagree over — the answer [Vocabulary.disagreement]
 * gives, from whichever of its three sources knew.
 *
 * [over] is what the reading says they argued about: two tags where the table named them, and the one
 * parameter or tag where the words' own settings gave it away.
 */
data class Disagreement(val over: List<String>, val severity: Int)

/** One antonym file: a list, so a pack may add pairs without reprinting ours. */
private data class AntonymPage(val pairs: List<Antonym>) {
    companion object {
        val CODEC: Codec<AntonymPage> = RecordCodecBuilder.create { instance ->
            instance.group(
                Antonym.CODEC.listOf().fieldOf("pairs").forGetter(AntonymPage::pairs),
            ).apply(instance, ::AntonymPage)
        }
    }
}

/**
 * Everything the Art knows how to say, and everything the world knows how to be: the words, the tags the
 * presets carry, and the antonym table. Loaded from datapacks under `data/<namespace>/art/`:
 *
 * | path | holds |
 * |---|---|
 * | `word/<name>.json` | one word — its tier, the aspects it may fill, its tag query |
 * | `preset_tags/<aspect>.json` | the tags every preset in that aspect carries |
 * | `antonyms/<page>.json` | pairs of tags that mean opposite things |
 * | `grammar/<name>.json` | one structural word, naming a production |
 * | `generation/<name>.json` | one weighted grammar the Art writes *out* of |
 *
 * One file per word so a pack can replace a single one; one per *aspect* of tags because tag weights are
 * only sensible read side by side, and stacked so a pack can retune a preset without reprinting its
 * neighbours.
 *
 * **A word that fails to load is reported, never silently absent** (§3.3). Problems land in [problems],
 * which `/age words` prints and `VocabularyCheck` fails the build over.
 */
data class Vocabulary(
    private val byName: Map<String, Word>,
    /** The structural words — `and`, `only`, `except` — keyed by what a writer says (§4.5). */
    private val structural: Map<String, GrammarWord>,
    private val tagsBySlot: Map<Aspect, PresetTags>,
    val antonyms: List<Antonym>,
    /** The script words are written in. Optional content — see [Script]. */
    val script: Script,
    /** How likely each word is to be found on a page. Nothing about resolution reads this. */
    val rarity: WordRarity,
    /** Which ink each word demands. The first reader of the resolver's cost number (design 7.1.1). */
    val ink: InkRequirement,
    /** The grammars the Art writes *out* of — books it could have written, names, repairs. */
    val generation: GenerationGrammars,
    /**
     * What a flaw of each kind earns towards the instability budget (design §5.0), by register key.
     *
     * Here rather than beside the manifestation prices because the charge is applied at *resolution*, and
     * resolution has the corpus and nothing else. A register nobody wrote a file for keeps its shipped
     * default, so an absent directory changes nothing.
     */
    val charges: Map<String, Int>,
    /** Which words came from registry content rather than from a `art/word/` file. See [isDerived]. */
    private val derivedIds: Set<Identifier>,
    /** How the world's own tags and facts become ours, per aspect — see [Derivation]. */
    val derivation: Map<Aspect, Derivation>,
    /** How a creature the world never offered arrives in one — see [Spawning]. */
    val spawning: Spawning,
    /** What could not be read, in the words a content author needs to hear. Empty in a healthy pack. */
    val problems: List<String>,
) {
    /** Distinct, because a derived word answers to both its bare path and its full id (see [load]). */
    val words: List<Word> get() = byName.values.distinct().sortedBy { it.name }

    /** The word a writer means by [name], or null if the corpus has never heard of it. */
    fun word(name: String): Word? = byName[name]

    /**
     * Whether this word was read off a block, biome or structure rather than authored (§8.1).
     *
     * What it buys is proportion: derived words outnumber authored ones by better than twenty to one, so
     * anything drawing a word uniformly draws content vocabulary essentially always. [WordRarity] treats
     * the whole derived corpus as one weighted entry because of this.
     */
    fun isDerived(word: Word): Boolean = word.id in derivedIds

    /** The words a pack authored, which are the ones worth naming individually in a rarity bucket. */
    val authoredWords: List<Word> get() = words.filterNot(::isDerived)

    /** The words read off content — one anonymous mass, deliberately. */
    val derivedWords: List<Word> get() = words.filter(::isDerived)

    /** Every structural word the Art knows, for `/age words` and for the grammar check. */
    val grammarWords: List<GrammarWord> get() = structural.values.sortedBy { it.name }

    /**
     * The structural word [name] spells, if it is one. Asked **before** [word], so a pack defining an
     * ordinary word called `and` cannot make the conjunction unsayable.
     */
    fun grammarWord(name: String): GrammarWord? = structural[name]

    /** What [preset] is like — an empty profile being a preset no word can currently reach. */
    fun profileOf(preset: AspectPreset): PresetProfile =
        tagsBySlot[preset.aspect]?.of(preset) ?: PresetTags.EMPTY_PROFILE

    /** The tags [preset] carries — empty being a preset no word can currently reach. */
    fun tagsOf(preset: AspectPreset): Map<String, Double> = profileOf(preset).tags

    /** How willingly the Art reaches for [preset] when nothing asked for it. */
    fun readinessOf(preset: AspectPreset): Double =
        profileOf(preset).readiness ?: PresetProfile.ORDINARY_READINESS

    /** Every tag anything in the world carries, which bounds what any word can meaningfully ask for. */
    val carriedTags: Set<String> get() = tagsBySlot.values.flatMap { it.carried }.toSet()

    /**
     * Tags **only a bound registry tag can grant** — nothing authored gives them and no fact derives one,
     * so a corpus read without a server carries them nowhere (`notes/the-tag-layer.md` §4).
     *
     * `ore` is the case: an ore *feature* is how vanilla places andesite and tuff as well, so what makes a
     * thing ore is the block, and what says a block is ore is `#minecraft:diamond_ores` and its seven
     * siblings. Offline that is unknowable, and an offline check asserting otherwise would be asserting
     * against a corpus the game never sees. Derived rather than listed, so a rule moving between the two
     * halves needs nothing here changed.
     */
    val tagsOnlyAServerGrants: Set<String>
        get() {
            val fromTags = derivation.values.flatMap { it.byTag.values.flatMap(Map<String, Double>::keys) }
            val fromFacts = derivation.values.flatMap { it.byKind.values.flatMap(Map<String, Double>::keys) }
            return fromTags.toSet() - fromFacts.toSet() - carriedTags
        }

    /**
     * **The curated pool** — everything a *vague* word may draw from in [aspect] (§8.2). A closed aspect's
     * authored presets; for an open one, exactly what `preset_tags/<aspect>.json` has an entry for.
     *
     * **The registry is deliberately not here**, which is what makes §8.2 structural rather than a rule to
     * remember: vagueness draws only from the curated pool because it has nothing else to draw from, and
     * the resolver's work is bounded by our curation rather than by the size of the modpack.
     */
    fun candidatesFor(aspect: Aspect): List<AspectPreset> {
        if (!aspect.open) return aspect.authored
        // Sorted, because a draw is made by index and nobody should change a world by reordering a file.
        // A closed aspect gets the same guarantee from its enum's declaration order.
        val described = tagsBySlot[aspect]?.described.orEmpty()
        return (described + weightedIn(aspect)).distinct().sorted().mapNotNull(aspect::presetFor)
    }

    /**
     * Every preset some word has an opinion about **by name** — see [Word.weights].
     *
     * Here because an open aspect's candidates are the presets its tag table describes, and a direct weight
     * on something the table has never heard of would otherwise score a preset that was never in the bag:
     * inert, and silently so. A weight has to *admit* a preset as well as rank it, which is the same
     * courtesy [Word.names] already gets — naming a thing being "the one way to reach something
     * `askableIn` leaves out".
     *
     * **Per aspect**, because an open aspect makes a preset out of any id it is handed: offering every
     * weighted id to every aspect turned a word's biomes into candidate structure sets.
     *
     * Computed once. A pack's authored words are a few dozen and derived ones carry no weights, so this is
     * a handful of ids however large the corpus grows.
     */
    private val weighted: Map<Aspect, Set<String>> by lazy {
        val words = byName.values.distinct()
        Aspect.entries.associateWith { aspect ->
            words.flatMap { it.weights[aspect]?.keys.orEmpty() }.toSet()
        }
    }

    private fun weightedIn(aspect: Aspect): Set<String> = weighted[aspect].orEmpty()

    /**
     * The curated pool less everything that opted out of being asked for — **what a sentence may actually
     * reach**, and so what a draw for an aspect nobody spoke to draws from.
     *
     * The two pools are separate because [candidatesFor] answers "what is there", which `VocabularyCheck`
     * needs in order to notice a preset that is neither askable nor pinned. Saying `askableInASentence =
     * false` and then leaving the preset in the bag a vague word draws from made it *rarer*, not
     * unreachable: the Spire's sky came up on one Age in three and took its dimension type with it.
     */
    fun askableIn(aspect: Aspect): List<AspectPreset> =
        candidatesFor(aspect).filter { it.askableInASentence }

    /**
     * The presets in [aspect] this word would keep, at its tier's strictness. A word that **names** a
     * preset never searches, which is what keeps derived vocabulary free at resolve time (§8.2) — and is
     * the one way to reach something [askableIn] leaves out, so a deliberate word still can.
     */
    /**
     * Whether anything in [aspect] answers [word] at all, either way — the question a **population** asks
     * where a preset aspect asks [carriersOf].
     *
     * A word may be entirely *negative* about a population and be perfectly well backed: `untouched` says
     * what must not be here, no structure set carries a tag for its own absence, and every one of them
     * answers the word.
     */
    fun answersIn(word: Word, aspect: Aspect): Boolean =
        candidatesFor(aspect).any { word.affinityIn(aspect, tagsOf(it)) != 0.0 }

    fun carriersOf(word: Word, aspect: Aspect): List<AspectPreset> {
        word.namedPreset(aspect)?.let { return listOf(it) }
        return askableIn(aspect).filter { word.acceptsOn(it, tagsOf(it)) }
    }

    /**
     * Whether anything in [aspect] would actually *act* on [parameter], as opposed to declaring it — "has
     * this word anything to do here at all?" for a word that steers rather than chooses. A word may narrow
     * presets in one aspect and only turn a knob in another, and treating the second as unbacked condemns
     * a sentence that works.
     */
    fun turnsAKnob(aspect: Aspect, parameter: String): Boolean =
        candidatesFor(aspect).any { it.honoursParameterNamed(parameter) } ||
            aspect.dials.any { it.name == parameter }

    /** What a flaw of this [register] earns towards the budget here — its shipped default unless a pack says. */
    fun earnedBy(register: Register): Int = charges[register.key] ?: register.base

    /** Whether these two tags are known opposites, and how badly. */
    fun opposition(first: String, second: String): Antonym? = antonyms.firstOrNull { antonym ->
        (antonym.first == first && antonym.second == second) || (antonym.first == second && antonym.second == first)
    }

    /**
     * **Whether two words can both stand**, and what they disagree over — asked of the words themselves,
     * so it holds before anything has been resolved and wherever they were laid.
     *
     * Three sources, most explanatory first. The **table** is an authored statement about *meaning*, which
     * is the only one of the three that can say `watery` and `dry` are opposites when the two words are
     * about different parts of the world entirely. The other two are read out of the words and need no
     * table at all:
     *
     * - **one parameter, two bands that cannot both be met** — `Setting.settle` returning null is the
     *   loudest disagreement there is, and until this it was the one nobody was asking about: `Repair`
     *   compared tags against the table and never looked at what a word set, so a drawn `temperate` beside
     *   a written `scorching` read as agreement.
     * - **one tag, wanted by one and pushed away by the other** — `sterile` against `verdant`, which no
     *   table has to know because both words already said so.
     *
     * **Two different values of one parameter are deliberately not here.** `motes=ash` against
     * `motes=embers` is a choice between answers rather than an impossibility, and the resolver already
     * prices a displacement where it happens. Only bands that provably cannot both hold are a contradiction
     * before the fact.
     */
    fun disagreement(first: Word, second: Word): Disagreement? =
        overATag(first, second) ?: overAParameter(first, second) ?: overAPushedTag(first, second)

    /** What the authored table knows — see [opposition]. */
    private fun overATag(first: Word, second: Word): Disagreement? =
        first.wanted.firstNotNullOfOrNull { wanted ->
            second.wanted.firstNotNullOfOrNull { against ->
                opposition(wanted, against)?.let { Disagreement(listOf(it.first, it.second), it.severity) }
            }
        }

    /** One axis both words bound, to bands that cannot both be met. */
    private fun overAParameter(first: Word, second: Word): Disagreement? =
        first.sets.firstNotNullOfOrNull { (parameter, mine) ->
            val theirs = second.sets[parameter] ?: return@firstNotNullOfOrNull null
            val asked = listOfNotNull(Setting.read(mine), Setting.read(theirs))
            val bothAreBands = asked.size == 2
            if (bothAreBands && Setting.settle(asked) == null) {
                Disagreement(listOf(parameter), Antonym.ORDINARY_SEVERITY)
            } else {
                null
            }
        }

    /** One tag one word asks for and the other asks against. */
    private fun overAPushedTag(first: Word, second: Word): Disagreement? =
        ((first.wanted intersect second.unwanted) + (second.wanted intersect first.unwanted))
            .firstOrNull()
            ?.let { Disagreement(listOf(it), Antonym.ORDINARY_SEVERITY) }

    companion object {
        /** Where a pack puts words. */
        const val WORD_DIRECTORY = "art/word"

        /** Where a pack puts the tags a aspect's presets carry, one file per aspect key. */
        const val PRESET_TAGS_DIRECTORY = "art/preset_tags"

        /** Where a pack puts the rules that read tags off the world itself, one file per aspect. */
        const val DERIVATION_DIRECTORY = "art/derivation"

        /** Where a pack puts antonym pages. */
        const val ANTONYM_DIRECTORY = "art/antonyms"

        /** What each contradiction earns towards the budget — the accumulation half of design §5.0. */
        const val CHARGE_DIRECTORY = "art/instability"

        /** Where a pack puts the structural words — one file per word, naming a production. */
        const val GRAMMAR_DIRECTORY = "art/grammar"

        private const val JSON_SUFFIX = ".json"

        /**
         * The corpus this server is currently running. Read fresh each time rather than cached: it happens
         * once when an Age is written, and a cache would need invalidating on `/reload`.
         */
        /**
         * The corpus this server is currently running, **built once and kept**.
         *
         * It used to rebuild on every call, which is eleven hundred words derived from the registries and
         * every authored word re-read off the resource manager — and callers reasonably assume a getter is
         * a getter. The desk asks for it on every action, and twice for a while.
         *
         * **Keyed on the resource manager's identity**, which is exactly the thing a datapack reload
         * replaces: `reloadResources` builds a fresh `MultiPackResourceManager`, so a reloaded pack misses
         * the cache and rebuilds, and nothing has to remember to invalidate anything.
         *
         * No lock. Two threads arriving together build it twice and one wins, which costs a rebuild that
         * was happening every call until now and cannot produce a wrong answer — `load` is pure in its
         * inputs.
         */
        @Volatile
        private var corpus: Pair<ResourceManager, Vocabulary>? = null

        fun of(server: MinecraftServer): Vocabulary {
            val resources = server.resourceManager
            corpus?.let { (from, known) -> if (from === resources) return known }
            return load(resources, server.registryAccess()).also { corpus = resources to it }
        }

        /**
         * The corpus in [resources] — the whole of the loading, and usable offline.
         *
         * Authored and derived words meet in one map, so nothing downstream can tell them apart. Authored
         * wins every collision, and the derived word stays reachable by its full id — the reverse would
         * let a block from some mod quietly redefine a word of the Art.
         */
        fun load(resources: ResourceManager, registries: HolderLookup.Provider? = null): Vocabulary {
            val problems = mutableListOf<String>()
            val authored = readWords(resources, problems)
            // After the words, so a domain claiming a page some word file also defines is reported rather
            // than silently winning or losing on map order.
            val pages = aimingPages(authored, problems)
            val tags = readPresetTags(resources, problems)
            val rules = readDerivations(resources, problems)
            val spawning = readSpawning(resources, problems)
            val antonyms = readAntonyms(resources, problems)
            // Blocks are built-in and always available; biomes and structures are datapack content, so a
            // corpus read without a server has §8's material half and neither population. Absent rather
            // than wrong, which is what lets `VocabularyCheck` stay offline.
            val fromRegistries = registries?.let {
                DerivedWords.biomes(it) + DerivedWords.structures(it) + DerivedWords.features(it)
            }.orEmpty()
            val fromContent = DerivedWords.materials() + DerivedWords.spawns(spawning.writable) + fromRegistries
            // **What the world says about itself, with what a pack said laid over it** — the tag half of
            // §8's "no per-mod work": a modded biome carrying `#minecraft:is_forest` is wooded without
            // anybody here having heard of it. Absent offline for want of bound tags, which is why the
            // rules that key on *facts* are the ones the offline checks may lean on.
            val derivedTags = registries?.let { DerivedTags.read(it, rules, problems) }.orEmpty()
            val described = Aspect.entries.associateWith { aspect ->
                (tags[aspect] ?: PresetTags(emptyMap())).over(derivedTags[aspect].orEmpty())
            }.filterValues { it.described.isNotEmpty() }
            val words = derived(fromContent) + authored + pages
            val structural = readGrammarWords(resources, problems)
            val script = Script.load(resources, problems)
            val rarity = WordRarity.load(resources, problems)
            val ink = InkRequirement.load(resources, problems)
            // After the words, since a grammar naming one that does not exist is the fault worth reporting.
            val generation = GenerationGrammars.load(
                resources,
                isAWord = { it in words || it in structural },
                problems,
            )
            for (problem in problems) Constants.LOG.error("Art vocabulary: {}", problem)
            // Authored wins every collision above, so a derived id that an authored word displaced is not
            // in `words` and must not be counted derived.
            val derivedIds = fromContent.map { it.id }.toSet() - authored.values.map { it.id }.toSet()
            val charges = readCharges(resources, problems)
            return Vocabulary(
                words, structural, described, antonyms, script, rarity, ink, generation, charges, derivedIds,
                rules, spawning, problems,
            )
        }

        /**
         * Derived words under the names a writer may say them by: the bare registry path, and always the
         * full `namespace:path` (§8.1.1).
         *
         * **A path two packs both ship stops being offered bare**, since giving it to whichever loaded
         * first would make what `creosote` means depend on mod load order. The full id still reaches both.
         * Not reported as a problem — two mods are allowed to both have creosote.
         */
        private fun derived(words: List<Word>): Map<String, Word> {
            // **Two derived words for one id are merged, never left to displace each other.** A placed
            // feature and a block can share an id — `minecraft:blue_ice` is both — and the same principle
            // already covers a fluid and its block: the id is the thing a writer points at, so one word
            // carries everything that thing can be. Left to collide, the later one silently won and the
            // bare name became ambiguous, which cost `blue_ice` its place in the corpus entirely.
            val words = words.groupBy { it.id }.map { (_, sharing) -> sharing.reduce(::mergedCapabilities) }
            val ambiguous = words.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
            return buildMap {
                for (word in words) {
                    put(word.id.toString(), word)
                    if (word.name !in ambiguous) put(word.name, word)
                }
            }
        }

        /** One referent's word said whole: everywhere it speaks, and every knob it turns. */
        private fun mergedCapabilities(word: Word, also: Word): Word = word.copy(
            aspects = word.aspects + also.aspects,
            sets = word.sets + also.sets,
            names = word.names ?: also.names,
        )

        /**
         * What each contradiction earns, from `art/instability/<register>.json`.
         *
         * Keyed by the register's own key rather than by the enum, so a file naming one this version does
         * not have is *reported* and skipped rather than failing the load — a pack written against a
         * newer register set should still give a working corpus.
         */
        private fun readCharges(resources: ResourceManager, problems: MutableList<String>): Map<String, Int> =
            buildMap {
                for ((file, resource) in resources.listResources(CHARGE_DIRECTORY) { it.path.endsWith(JSON_SUFFIX) }) {
                    val key = idOf(file, CHARGE_DIRECTORY).path
                    val read: Result<Int> = runCatching {
                        resource.open().use { stream ->
                            JsonParser.parseReader(stream.reader()).asJsonObject["earns"].asInt
                        }
                    }
                    read.onSuccess { put(key, it) }
                        .onFailure { problems += "'$file' does not say what it earns: ${it.message}" }
                }
            }

        /**
         * **One aiming page per aspect**, synthesised rather than authored (`the-world-model.md` §3).
         *
         * A naming word is a handle on a group of properties and says nothing itself, so there is nothing
         * for a file to carry that the aspect does not already know — and a page authored beside its aspect
         * is two statements of one thing, which is the drift this forecloses. A word file of the same name
         * is therefore a content collision and is called out as one.
         *
         * This replaced `art/domain/<name>.json`, a layer that named the aspects one page opened. It could only
         * ever name *whole* aspects, so the redraw it was wanted for — a page for the water alone, where
         * `murk` is one property of the air — was the one thing it could not do.
         */
        private fun aimingPages(authored: Map<String, Word>, problems: MutableList<String>): Map<String, Word> =
            Aspect.entries.associate { aspect ->
                if (aspect.page in authored) {
                    problems += "'${aspect.page}' is both an aspect and a word; an aspect already is its page"
                }
                aspect.page to Word(
                    id = aspect.page.location(),
                    tier = Tier.RESTRICTIVE,
                    aspects = setOf(aspect),
                    query = emptyMap(),
                )
            }

        private fun readWords(resources: ResourceManager, problems: MutableList<String>): Map<String, Word> {
            val words = mutableMapOf<String, Word>()
            for ((file, resource) in resources.listResources(WORD_DIRECTORY) { it.path.endsWith(JSON_SUFFIX) }) {
                val id = idOf(file, WORD_DIRECTORY)
                val word = parse(resource, file, Word.mapCodec(id).codec(), problems) ?: continue
                // Two packs both defining "floating" would otherwise leave the winner to map iteration
                // order, so it is called out as the content collision it is.
                val existing = words[word.name]
                if (existing != null && existing.id != word.id) {
                    problems += "two words are both called '${word.name}': ${existing.id} and ${word.id}"
                }
                words[word.name] = word
            }
            return words
        }

        /**
         * How a creature the world never offered arrives in one — one file, stacked so a pack may retune a
         * single creature without reprinting the rest.
         */
        private fun readSpawning(resources: ResourceManager, problems: MutableList<String>): Spawning {
            val file = Identifier.fromNamespaceAndPath(Constants.MOD_ID, Spawning.FILE)
            var standing = Spawning()
            for (layer in resources.getResourceStack(file)) {
                val read = parse(layer, file, Spawning.CODEC, problems) ?: continue
                standing = standing.mergedWith(read)
            }
            return standing
        }

        /**
         * The derivation rules, one file per aspect — what the world's own tags and facts mean in ours.
         *
         * Stacked like the tables they feed, so a pack may add a rule without reprinting the file; an
         * aspect with no file derives nothing, which is how an aspect opts out.
         */
        private fun readDerivations(
            resources: ResourceManager,
            problems: MutableList<String>,
        ): Map<Aspect, Derivation> {
            val stacks = resources.listResourceStacks(DERIVATION_DIRECTORY) { it.path.endsWith(JSON_SUFFIX) }
            val rules = mutableMapOf<Aspect, Derivation>()
            for ((file, layers) in stacks) {
                val key = idOf(file, DERIVATION_DIRECTORY).path
                val aspect = Aspect.entries.firstOrNull { it.key == key }
                if (aspect == null) {
                    problems += "$file names no aspect ('$key'); aspects are ${Aspect.entries.joinToString(" ") { it.key }}"
                    continue
                }
                for (layer in layers) {
                    val read = parse(layer, file, Derivation.CODEC, problems) ?: continue
                    val standing = rules[aspect]
                    rules[aspect] = standing?.let {
                        Derivation(it.byTag + read.byTag, it.byKind + read.byKind)
                    } ?: read
                }
            }
            return rules
        }

        /**
         * The tag tables, stacked lowest-priority pack first so a higher one overrides preset by preset
         * rather than replacing a whole aspect's table.
         */
        private fun readPresetTags(
            resources: ResourceManager,
            problems: MutableList<String>,
        ): Map<Aspect, PresetTags> {
            val stacks = resources.listResourceStacks(PRESET_TAGS_DIRECTORY) { it.path.endsWith(JSON_SUFFIX) }
            val bySlot = mutableMapOf<Aspect, MutableMap<String, PresetProfile>>()
            for ((file, layers) in stacks) {
                val slotKey = idOf(file, PRESET_TAGS_DIRECTORY).path
                val aspect = Aspect.entries.firstOrNull { it.key == slotKey }
                if (aspect == null) {
                    problems += "$file names no aspect ('$slotKey'); aspects are ${Aspect.entries.joinToString(" ") { it.key }}"
                    continue
                }
                val merged = bySlot.getOrPut(aspect) { mutableMapOf() }
                for (layer in layers) {
                    val table = parse(layer, file, PresetTags.CODEC, problems) ?: continue
                    for (preset in table.described) {
                        // An open aspect takes any well-formed id: an entry for a block from a mod that is
                        // not installed is a pack covering more ground than this instance runs, not a
                        // mistake. A closed aspect still has to name one of its own.
                        if (aspect.presetFor(preset) == null) {
                            problems += if (aspect.open) {
                                "$file tags '$preset', which is not a `namespace:path` id"
                            } else {
                                "$file tags '$preset', which is no ${aspect.key}"
                            }
                            continue
                        }
                        val standing = merged[preset]
                        merged[preset] = standing?.mergedWith(table.byKey(preset)) ?: table.byKey(preset)
                    }
                }
            }
            return bySlot.mapValues { (_, merged) -> PresetTags(merged.toMap()) }
        }

        private fun readGrammarWords(
            resources: ResourceManager,
            problems: MutableList<String>,
        ): Map<String, GrammarWord> {
            val structural = mutableMapOf<String, GrammarWord>()
            for ((file, resource) in resources.listResources(GRAMMAR_DIRECTORY) { it.path.endsWith(JSON_SUFFIX) }) {
                val id = idOf(file, GRAMMAR_DIRECTORY)
                val word = parse(resource, file, GrammarWord.mapCodec(id).codec(), problems) ?: continue
                val existing = structural[word.name]
                if (existing != null && existing.id != word.id) {
                    problems += "two structural words are both called '${word.name}': ${existing.id} and ${word.id}"
                }
                structural[word.name] = word
            }
            return structural
        }

        private fun readAntonyms(resources: ResourceManager, problems: MutableList<String>): List<Antonym> {
            val stacks = resources.listResourceStacks(ANTONYM_DIRECTORY) { it.path.endsWith(JSON_SUFFIX) }
            return stacks.entries.sortedBy { it.key.toString() }.flatMap { (file, layers) ->
                layers.flatMap { layer -> parse(layer, file, AntonymPage.CODEC, problems)?.pairs.orEmpty() }
            }
        }

        /** A file's id without its directory or suffix: `…/art/word/floating.json` → `…:floating`. */
        private fun idOf(file: Identifier, directory: String): Identifier =
            Identifier.fromNamespaceAndPath(
                file.namespace,
                file.path.removePrefix("$directory/").removeSuffix(JSON_SUFFIX),
            )

        private fun <T> parse(
            resource: Resource,
            file: Identifier,
            codec: Codec<T>,
            problems: MutableList<String>,
        ): T? = ResourceParsing.parse(resource, file, codec, problems)
    }
}

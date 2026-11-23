package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.datapack.PerReload
import co.voik.agesandtheart.datapack.ResourceParsing
import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Materials
import co.voik.agesandtheart.age.aspect.Taggable
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.honoursDial
import co.voik.agesandtheart.age.aspect.Spawning
import co.voik.agesandtheart.age.reading.Headings
import co.voik.agesandtheart.age.reading.StructureWord
import co.voik.agesandtheart.age.reading.StructureWords
import co.voik.agesandtheart.age.reading.Usage
import co.voik.agesandtheart.age.reading.WorldMembership
import co.voik.agesandtheart.age.reading.Writing
import co.voik.agesandtheart.age.reading.Written
import co.voik.agesandtheart.age.readout.Readout
import co.voik.agesandtheart.age.readout.ReadoutClause
import co.voik.agesandtheart.age.resolution.Resolution
import co.voik.agesandtheart.age.words.DerivedWords
import co.voik.agesandtheart.age.words.Word
import co.voik.agesandtheart.age.words.Words
import co.voik.agesandtheart.age.resolution.WorldFacts
import co.voik.agesandtheart.age.word.generation.GenerationGrammars
import com.google.gson.JsonParser
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.HolderLookup
import net.minecraft.core.Registry
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.packs.resources.ResourceManager

/** Two tags that mean opposite things, so two words wanting one each are at odds wherever they land together. */
data class OpposedTags(val first: String, val second: String) {
    companion object {
        val CODEC: Codec<OpposedTags> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.STRING.fieldOf("first").forGetter(OpposedTags::first),
                Codec.STRING.fieldOf("second").forGetter(OpposedTags::second),
            ).apply(instance, ::OpposedTags)
        }
    }
}

/** One file of opposed tags: a list, so a pack may add pairs without reprinting ours. */
private data class OpposedTagsFile(val pairs: List<OpposedTags>) {
    companion object {
        val CODEC: Codec<OpposedTagsFile> = RecordCodecBuilder.create { instance ->
            instance.group(
                OpposedTags.CODEC.listOf().fieldOf("pairs").forGetter(OpposedTagsFile::pairs),
            ).apply(instance, ::OpposedTagsFile)
        }
    }
}

/**
 * Everything the Art knows how to say, and everything the world knows how to be: the words, the tags the
 * presets carry, and the opposed-tags table. Loaded from datapacks under `data/<namespace>/art/`:
 *
 * | path | holds |
 * |---|---|
 * | `words/<name>.json`, `concept/`, `kind/` | the words, what they offer and take, and the kinds they share ([Words]) |
 * | `preset_tags/<aspect>.json` | the tags every preset in that aspect carries |
 * | `opposed_tags/<page>.json` | pairs of tags that mean opposite things |
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
    /** Every word, concept and kind, the structure words and the words the game's content was given among them. */
    val words: Words,
    private val tagsBySlot: Map<Aspect, PresetTags>,
    val opposedTags: List<OpposedTags>,
    /** The script words are written in. Optional content — see [Script]. */
    val script: Script,
    /** How likely each word is to be found on a page. Nothing about resolution reads this. */
    val rarity: WordRarity,
    /** Which ink each word demands. The first reader of the resolver's cost number (design 7.1.1). */
    val ink: InkRequirement,
    /** What a village writer keeps in stock, by pool. Nothing about resolution reads this. */
    val stock: WriterStock,
    /** The grammars the Art writes *out* of — books it could have written, names, repairs. */
    val generation: GenerationGrammars,
    /** How the world's own tags and facts become ours, per aspect — see [Derivation]. */
    val derivation: Map<Aspect, Derivation>,
    /** What a block may be as the rock or the ground, for a word asking for one by tag — see [MaterialTable]. */
    val materials: MaterialTable,
    /** How a creature the world never offered arrives in one — see [Spawning]. */
    val spawning: Spawning,
    /** Words that have gone, and what they became. */
    val retired: RetiredWords,
    /** Names of worldgen a pack ships that the registries this was loaded over did not hold. See [awaitsAServer]. */
    private val shippedButNotLoaded: Set<String>,
    /** What could not be read, in the words a content author needs to hear. Empty in a healthy pack. */
    val problems: List<String>,
    /** The registries this was loaded over, which say what each member honours; null offline. */
    private val registries: HolderLookup.Provider? = null,
) : WorldFacts {
    /** Every word once. */
    val allWords: List<Word> get() = words.all

    /** [allWords] by the rarity bucket each is found in, worked out once: a found page asks for it on every draw. */
    val wordsByBucket: Map<RarityBucket?, List<Word>> by lazy { allWords.groupBy(::bucketOf) }

    /** What [word] costs to write. */
    fun priceOf(word: Word): Int = words.priceOf(word)

    /** Whether [member] does anything with [parameter], as its code answers; offline, a registry entry is taken to. */
    fun honours(member: Taggable, parameter: Parameter): Boolean = member.honoursDial(parameter, registries)

    /** The ink [word] demands: its referent's tags where this was loaded over registries, else the authored lists. */
    fun inkFor(word: Word): InkTier = inks[word.id] ?: ink.tierFor(word, registries)

    /** Every word's ink, worked out once: a referent's tags are a registry walk, and a draw asks of every word. */
    private val inks: Map<Identifier, InkTier> by lazy { allWords.associate { it.id to ink.tierFor(it, registries) } }

    /** The rarity bucket [word] is found in. */
    fun bucketOf(word: Word): RarityBucket? = rarity.bucketOf(word, inkFor(word))

    /**
     * The word a writer means by [name], or null if the corpus has never heard of it. A word answering to the name
     * now wins over one retired from it, so `drowned` is the creature; an id keeps its retirement ([successorId]).
     */
    fun word(name: String): Word? = words.word(name) ?: retired.successorOf(name)?.let(words::word)

    /** The word a page or a learned set names by [id], or what it was retired into; null where there is no such word now. */
    fun word(id: Identifier): Word? {
        val named = (words.word(id.toString()) ?: words.word(id.path))?.takeIf { it.id == id }
        return named ?: successorId(id)?.let(::word)
    }

    /**
     * Whether [id] still names something a page, a book or a learned set may carry: a word, or one retired into words.
     * What does not is gone, and is cleared out wherever it is found.
     */
    fun stillNames(id: Identifier): Boolean = word(id) != null || successorIds(id) != null

    /** Every retired id that leads to a word now, and the word's id. */
    val renamed: Map<Identifier, Identifier> by lazy {
        retired.names.mapNotNull(RetiredWords::idOf)
            .mapNotNull { old -> successorId(old)?.let { old to it } }
            .toMap()
    }

    /**
     * The id [id] has become, where it was retired: one of our words by its name, and a derived word of another
     * namespace by its whole id (`minecraft:birch_checked`), so a retired name of ours never reaches a creature's.
     */
    fun successorId(id: Identifier): Identifier? {
        val retiredAs = if (id.namespace == Constants.MOD_ID) id.path else id.toString()
        return retired.successorOf(retiredAs)?.let(words::word)?.id
    }

    /**
     * Whether [name] would be a word on a server and is not one here: a placed feature, biome or structure
     * set a pack ships as worldgen JSON, over registries that never loaded it. Offline that is exactly the
     * pack's own worldgen; on a server it is nothing.
     */
    fun awaitsAServer(name: String): Boolean = words.word(name) == null && name in shippedButNotLoaded

    /**
     * Whether this word was read off a block, biome or structure rather than authored (§8.1).
     *
     * What it buys is proportion: derived words outnumber authored ones by better than twenty to one, so
     * anything drawing a word uniformly draws content vocabulary essentially always. [WordRarity] treats
     * the whole derived corpus as one weighted entry because of this.
     */
    fun isDerived(word: Word): Boolean = word.id in words.derived

    /** The words a pack authored, which are the ones worth naming individually in a rarity bucket. */
    val authoredWords: List<Word> get() = allWords.filterNot(::isDerived)

    /** The words read off content — one anonymous mass, deliberately. */
    val derivedWords: List<Word> get() = allWords.filter(::isDerived)

    /** Whether [word] is a structure word — `age`, `and`, `no` — which binds the pages around it rather than describing. */
    fun isAStructureWord(word: Word): Boolean = word.senses.any { it.behaviour != null }

    /** Whether [word] is `age`, which closes a book and hands what reaches it to the whole Age. */
    fun isTheAge(word: Word): Boolean = word.senses.any { it.behaviour == StructureWord.Age.id }

    /** Whether [pages] spell a book: one of them is `age`. */
    fun spellsABook(pages: List<String>): Boolean = pagesNow(pages).any { page -> word(page)?.let(::isTheAge) == true }

    /** The words [name] is read as where it is a page of ours retired into several, or null. */
    fun phraseOf(name: String): List<String>? = if (words.word(name) != null) null else retired.phraseOf(name)

    /** The ids [id] has become: one where it was renamed, several where it was retired into a phrase, null where neither. */
    fun successorIds(id: Identifier): List<Identifier>? {
        successorId(id)?.let { return listOf(it) }
        if (id.namespace != Constants.MOD_ID) return null
        return phraseOf(id.path)?.mapNotNull { words.word(it)?.id }?.takeIf { it.isNotEmpty() }
    }

    /** [pages] as the words they are read as now: a page retired into several words is them, one renamed its successor. */
    fun pagesNow(pages: List<String>): List<String> = pages.flatMap { page ->
        phraseOf(page) ?: listOf(if (words.word(page) != null) page else retired.successorOf(page) ?: page)
    }

    /** Which members of each part a word may name, as the reader asks it. */
    val membership: WorldMembership by lazy { WorldMembership(this) }

    /**
     * Whether [word] brings what a dangling page may never be drawn onto: anything kept out of loot or held back from
     * the channels, so a flaw cannot win back a reward.
     */
    fun isGated(word: Word): Boolean = gated[word.id] ?: isGatedWhenAsked(word)

    private fun isGatedWhenAsked(word: Word): Boolean {
        val keptOut = word.name in rarity.keptOutOfLoot
        fun carries(tag: Identifier) = registries?.carriesTagNamed(word, tag) == true
        return keptOut || carries(CannotAppearInLoot.TAG_NAME) || carries(Withheld.TAG_NAME)
    }

    /** Whether each word is gated, worked out once, since a dangling page asks it of every word that could take it. */
    private val gated: Map<Identifier, Boolean> by lazy { allWords.associate { it.id to isGatedWhenAsked(it) } }

    /** Rows of pages made into Ages. */
    val writing: Writing by lazy { Writing(words, this, membership, ::isGated) }

    /** [pages] read, drawn and resolved at [seed], a retired page read as what it became. */
    fun written(pages: List<String>, seed: Long): Written = writing.written(pagesNow(pages), seed)

    /** [pages] made into an Age at [seed]. */
    fun resolve(pages: List<String>, seed: Long): Resolution = written(pages, seed).resolution

    /** What [written] reads as: each thing a clause, what held of it, and what `age` handed out. */
    fun readout(written: Written): List<ReadoutClause> = Readout.of(written.reading, written.drawn, written.resolution, words)

    /** Which words take which, as the reader's own rule answers it. */
    val usage: Usage by lazy { Usage(words, membership) }

    /** The parts of the world each word reaches, and each page of ours retired into a phrase, as a client is told. */
    val reach: Map<Identifier, Set<Aspect>> by lazy {
        val ofWords = allWords.filterNot(::isAStructureWord).associate { it.id to usage.partsDescribed(it) }
        val ofPhrases = retired.phraseNames.mapNotNull { name ->
            val phrase = phraseOf(name)?.mapNotNull(words::word)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, name) to phrase.flatMap(usage::partsDescribed).toSet()
        }
        ofWords + ofPhrases
    }

    /**
     * The names of every word with something [head] takes, worked out once per head: the writer's desk asks it of every
     * clause of every book it proposes, and the answer is the vocabulary's alone.
     */
    fun wordsLandingOn(head: Word): Set<String> = landingOn.computeIfAbsent(head.name) {
        allWords.filter { usage.takes(head, it) }.map { it.name }.toSet()
    }

    private val landingOn = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    /** What each word applies to, for a word list ordered under it and a page's tooltip ([Headings.applying]). Worked out once. */
    val applying: Map<Identifier, Headings.Applying> by lazy { Headings.applying(usage) }

    /** The words each word applies to, for a word list ordered under them ([Headings.appliesTo]). */
    val appliesTo: Map<Identifier, List<Identifier>> by lazy {
        applying.mapValues { it.value.words }.filterValues { it.isNotEmpty() }
    }

    /** How many of the game's own members each part of the world holds ([Headings.ofTheGame]). */
    val ofTheGame: Map<Aspect, Int> by lazy { Headings.ofTheGame(words) }

    /** The smallest group each of the game's own members belongs to ([Headings.clustersOf]). */
    val clusters: Map<Identifier, Identifier> by lazy { Headings.clustersOf(usage) }

    /** The part of the world each heading belongs to ([Headings.partsOf]). */
    val headingParts: Map<Identifier, Aspect> by lazy { Headings.partsOf(words) }

    /** The words naming members of a part of the world ([Headings.members]). */
    val namingMembers: Set<Identifier> by lazy { Headings.members(words) }

    /** The words naming a whole part of the world ([Headings.parts]). */
    val namingParts: Set<Identifier> by lazy { Headings.parts(words) }

    /** What [preset] is like — an empty profile being a preset no word can currently reach. */
    fun profileOf(preset: Taggable): PresetProfile =
        tagsBySlot[preset.aspect]?.of(preset) ?: PresetTags.EMPTY_PROFILE

    /** The tags [member] carries — empty being a preset no word can currently reach. */
    override fun tagsOf(member: Taggable): Map<String, Double> = profileOf(member).tags

    /**
     * Whether [key] is an entry of the registry [aspect]'s members are held in, where this was loaded over registries: a
     * member a word may name outright, though the curated pool ([candidatesFor]) leaves it out.
     */
    fun isAnEntryOf(aspect: Aspect, key: String): Boolean = key in registryEntries[aspect].orEmpty()

    private val registryEntries: Map<Aspect, Set<String>> by lazy {
        Aspect.entries.associateWith { aspect ->
            val lookup = aspect.presetsAreEntriesOf?.let { registry -> registries?.lookup(registry)?.orElse(null) }
            lookup?.listElementIds()?.map { it.identifier().toString() }?.toList()?.toSet().orEmpty()
        }
    }

    /**
     * Whether [member] is in the world **anyway**, so naming it asks for more of it rather than for it at
     * all — see [PresetProfile.presentAnyway], which is what the mention bump turns on.
     */
    override fun isPresentAnyway(member: Taggable): Boolean = profileOf(member).presentAnyway

    fun isThereByDefault(aspect: Aspect, key: String): Boolean =
        tagsBySlot[aspect]?.byKey(key)?.presentAnyway ?: true

    /** How willingly the Art reaches for [member] when nothing asked for it. */
    override fun readinessOf(member: Taggable): Double =
        profileOf(member).readiness ?: PresetProfile.ORDINARY_READINESS

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
    override fun candidatesFor(aspect: Aspect): List<Taggable> = candidates.computeIfAbsent(aspect) {
        if (!aspect.open) return@computeIfAbsent aspect.authored
        // Sorted, because a draw is made by index and nobody should change a world by reordering a file.
        // A closed aspect gets the same guarantee from its enum's declaration order.
        val described = tagsBySlot[aspect]?.described.orEmpty()
        described.distinct().sorted().mapNotNull(aspect::presetFor)
    }

    /** Each aspect's [candidatesFor], worked out once: every book resolved asks for them. */
    private val candidates = java.util.concurrent.ConcurrentHashMap<Aspect, List<Taggable>>()

    /**
     * The curated pool less everything kept from broad words — **what a vague word may land on**, and so
     * what a draw for an aspect nobody spoke to draws from. A word naming a member outright still reaches
     * it.
     *
     * The two pools are separate because [candidatesFor] answers "what is there", which `VocabularyCheck`
     * needs in order to notice a preset that is neither available to broad words nor pinned. Leaving such a
     * preset in the bag a vague word draws from made it *rarer*, not unreachable: the Spire's sky came up
     * on one Age in three and took its dimension type with it.
     */
    override fun availableToBroadWordsIn(aspect: Aspect): List<Taggable> = availableToBroadWords.computeIfAbsent(aspect) {
        candidatesFor(aspect).filter(::isAvailableToBroadWords)
    }

    private val availableToBroadWords = java.util.concurrent.ConcurrentHashMap<Aspect, List<Taggable>>()

    /** Whether the pack says what [key], a member of [aspect], is like — so a word may reach it by what it is. */
    fun describes(aspect: Aspect, key: String): Boolean = key in tagsBySlot[aspect]?.described.orEmpty()

    /** Whether [preset] and its `preset_tags` entry both allow a broad word to land on it. */
    fun isAvailableToBroadWords(preset: Taggable): Boolean =
        preset.availableToBroadWords && profileOf(preset).availableToBroadWords

    /**
     * Whether anything in [aspect] would actually *act* on [parameter], as opposed to declaring it — "has
     * this word anything to do here at all?" for a word that steers rather than chooses. A word may narrow
     * presets in one aspect and only turn a parameter in another, and treating the second as unbacked condemns
     * a sentence that works.
     */
    override fun turnsAParameter(aspect: Aspect, parameter: String): Boolean =
        candidatesFor(aspect).any { it.honoursParameterNamed(parameter) } ||
            aspect.parameters.any { it.name == parameter }

    /** The pair naming these two tags as opposites, either way round, or null where none does. */
    fun opposition(first: String, second: String): OpposedTags? = opposedTags.firstOrNull { pair ->
        (pair.first == first && pair.second == second) || (pair.first == second && pair.second == first)
    }

    override fun areOpposed(first: String, second: String): Boolean = opposition(first, second) != null

    override fun answers(named: String, query: String): Boolean =
        Materials.isQuery(query) && (materials.carries(named, query) || flowingSubstances.carries(named, query))

    override fun drawnMaterial(query: String, parameter: String, seed: Long): String? =
        materials.drawFor(query, seed, MaterialTable.mayDrawFor(parameter))

    override fun drawnSubstance(query: String, flows: Boolean, seed: Long): String? =
        if (flows) flowingSubstances.drawFor(query, seed, Materials::holdsAFluid) else materials.drawFor(query, seed)

    /** What a spring may run with, asked for by tag: the sea's own table, which is the handful of things that pour. */
    private val flowingSubstances: MaterialTable by lazy {
        MaterialTable(tags = tagsBySlot[Aspect.SEA] ?: PresetTags(emptyMap()), withheld = emptySet())
    }

    companion object {
        /** Where a pack puts the tags a aspect's presets carry, one file per aspect key. */
        const val PRESET_TAGS_DIRECTORY = "art/preset_tags"

        /** Where a pack puts the rules that read tags off the world itself, one file per aspect. */
        const val DERIVATION_DIRECTORY = "art/derivation"

        /**
         * The one file in [PRESET_TAGS_DIRECTORY] and [DERIVATION_DIRECTORY] that names no aspect: the
         * blocks a rock or ground may be made of ([MaterialTable]).
         */
        const val MATERIALS = "materials"

        /** Where a pack puts its opposed tags. */
        const val OPPOSED_TAGS_DIRECTORY = "art/opposed_tags"

        /**
         * The corpus this server is currently running, **built once per datapack load** — eleven hundred
         * words derived from the registries and every authored word read off the resource manager, which
         * the desk would otherwise pay on every action.
         */
        private val corpus = PerReload { server -> load(server.resourceManager, server.registryAccess()) }

        fun of(server: MinecraftServer): Vocabulary = corpus.of(server)

        /**
         * The corpus in [resources] — the whole of the loading, and usable offline.
         *
         * Authored and derived words meet in one map, so nothing downstream can tell them apart. Authored
         * wins every collision, and the derived word stays reachable by its full id — the reverse would
         * let a block from some mod quietly redefine a word of the Art.
         */
        fun load(resources: ResourceManager, registries: HolderLookup.Provider? = null): Vocabulary {
            val problems = mutableListOf<String>()
            val tags = readPresetTags(resources, problems)
            val rules = readDerivations(resources, problems)
            val spawning = readSpawning(resources, problems)
            val opposedTags = readOpposedTags(resources, problems)
            // **What the world says about itself, with what a pack said laid over it** — the tag half of
            // §8's "no per-mod work": a modded biome carrying `#minecraft:is_forest` is wooded without
            // anybody here having heard of it. Absent offline for want of bound tags, which is why the
            // rules that key on *facts* are the ones the offline checks may lean on.
            val derivedTags = registries?.let { DerivedTags.read(it, rules, problems) }.orEmpty()
            val described = Aspect.entries.associateWith { aspect ->
                (tags[aspect] ?: PresetTags(emptyMap())).over(derivedTags[aspect].orEmpty())
            }.filterValues { it.described.isNotEmpty() }
            val materials = materialTable(resources, registries, problems)
            val retired = RetiredWords.load(resources, problems)
            val script = Script.load(resources, problems)
            val rarity = WordRarity.load(resources, problems)
            val ink = InkRequirement.load(resources, problems)
            val stock = WriterStock.load(resources, problems)
            val shippedButNotLoaded = worldgenShippedButNotLoaded(resources, registries)
            val world = Vocabulary(
                Words.NONE, described, opposedTags, script, rarity, ink, stock, GenerationGrammars.NONE,
                rules, materials, spawning, retired, shippedButNotLoaded, problems, registries,
            )
            // Blocks and creatures are built in and always there; biomes, structures and placed features are datapack
            // content, so a corpus read without a server has neither. Absent rather than wrong, which is what lets the
            // offline checks stay offline. A feature's word reads its tags, so the world is stood up first.
            val features = world.candidatesFor(Aspect.FEATURES).associateBy { it.key }
            fun tagsOfFeature(key: String) = features[key]?.let(world::tagsOf).orEmpty()
            val fromRegistries = registries?.let {
                DerivedWords.biomes(it) + DerivedWords.structures(it) + DerivedWords.features(it, ::tagsOfFeature)
            }.orEmpty()
            fun isRetired(word: Word) = word.id.toString() in retired.names
            val derived = (DerivedWords.blocks() + DerivedWords.designs() + DerivedWords.creatures(spawning.writable) + fromRegistries)
                .filterNot(::isRetired)
            val words = Words.load(resources, StructureWords.IDS, derived)
            problems += words.problems.map { it.toString() }
            for (name in retired.names) {
                if (retired.successorOf(name)?.let(words::word) == null) problems += "retired word '$name' leads to no word that exists"
            }
            // After the words, since a grammar naming one that does not exist is the fault worth reporting.
            val generation = GenerationGrammars.load(
                resources,
                isAWord = { words.word(it) != null || it in shippedButNotLoaded },
                problems,
            )
            for (problem in problems) Constants.LOG.error("Art vocabulary: {}", problem)
            return world.copy(words = words, generation = generation, problems = problems.toList())
        }

        /**
         * The material table: what the pack authored in `preset_tags/materials.json`, laid over what
         * `derivation/materials.json` reads off every block. An authored block a world cannot be made of is
         * reported rather than kept, since a query drawing it would build an Age nobody can stand in.
         */
        private fun materialTable(
            resources: ResourceManager,
            registries: HolderLookup.Provider?,
            problems: MutableList<String>,
        ): MaterialTable {
            val authored = readStacked(resources, PRESET_TAGS_DIRECTORY, PresetTags.CODEC, problems)
                .fold(PresetTags(emptyMap())) { standing, layer -> standing.stackedWith(layer) }
            for (block in authored.described.filterNot(Materials::makesAWorld)) {
                problems += "$PRESET_TAGS_DIRECTORY/$MATERIALS.json tags '$block', which no world can be made of"
            }
            val rules = readStacked(resources, DERIVATION_DIRECTORY, MaterialDerivation.CODEC, problems)
                .fold(MaterialDerivation.EMPTY, MaterialDerivation::over)
            val (derived, withheldWhereTagsAreBound) =
                if (registries == null) emptyMap<String, Map<String, Double>>() to emptySet()
                else DerivedTags.materials(rules, problems)
            // **The fence holds offline too.** Tags are bound only on a server, but a fence tag this pack
            // ships is a file it can read — so Scrivener and the offline checks withhold what the game does.
            val withheldByFile = rules.withheldFromBroadWords.flatMap { blocksListedBy(resources, it) }
            val kept = PresetTags(authored.described.filter(Materials::makesAWorld).associateWith(authored::byKey))
            return MaterialTable(kept.over(derived), withheldWhereTagsAreBound + withheldByFile)
        }

        /**
         * Every block id a block tag file lists, following the tags it includes — read from the files rather
         * than from a bound registry. A tag no pack here ships lists nothing, which is right offline: it
         * belongs to content that is not installed.
         */
        private fun blocksListedBy(
            resources: ResourceManager,
            tag: String,
            seen: MutableSet<String> = mutableSetOf(),
        ): List<String> {
            if (!seen.add(tag)) return emptyList()
            val id = Identifier.tryParse(tag.removePrefix(Materials.QUERY_MARK)) ?: return emptyList()
            val file = Identifier.fromNamespaceAndPath(id.namespace, "$BLOCK_TAGS_DIRECTORY/${id.path}.json")
            return resources.getResourceStack(file).flatMap { layer ->
                val values = layer.openAsReader().use { JsonParser.parseReader(it) }.asJsonObject
                    .getAsJsonArray(TAG_VALUES)?.toList().orEmpty()
                values.flatMap { value ->
                    // An entry is an id, `#` and a tag, or `{ "id": …, "required": false }`.
                    val spelled = if (value.isJsonObject) value.asJsonObject.get(TAG_ENTRY_ID).asString else value.asString
                    if (spelled.startsWith(Materials.QUERY_MARK)) blocksListedBy(resources, spelled, seen)
                    else listOf(spelled)
                }
            }
        }

        private const val BLOCK_TAGS_DIRECTORY = "tags/block"
        private const val TAG_VALUES = "values"
        private const val TAG_ENTRY_ID = "id"

        /** Every layer of `<directory>/materials.json` across the packs, lowest priority first. */
        private fun <T : Any> readStacked(
            resources: ResourceManager,
            directory: String,
            codec: Codec<T>,
            problems: MutableList<String>,
        ): List<T> = resources.listResourceStacks(directory, ResourceParsing::isJson)
            .filterKeys { ResourceParsing.nameUnder(it, directory) == MATERIALS }
            .flatMap { (file, layers) -> layers.mapNotNull { ResourceParsing.parse(it, file, codec, problems) } }

        /**
         * Every name — full id and bare path — of worldgen in [resources] that [registries] does not hold.
         * A grammar may name one without it being reported as no word, since it is a word wherever the pack
         * is really loaded.
         */
        private fun worldgenShippedButNotLoaded(resources: ResourceManager, registries: HolderLookup.Provider?): Set<String> =
            // The three registries [DerivedWords] makes words of.
            (
                shippedButNotLoaded(resources, registries, Registries.PLACED_FEATURE) +
                    shippedButNotLoaded(resources, registries, Registries.BIOME) +
                    shippedButNotLoaded(resources, registries, Registries.STRUCTURE_SET)
                ).toSet()

        private fun <T : Any> shippedButNotLoaded(
            resources: ResourceManager,
            registries: HolderLookup.Provider?,
            registry: ResourceKey<out Registry<T>>,
        ): List<String> {
            val directory = Registries.elementsDirPath(registry)
            val loaded = registries?.lookup(registry)?.orElse(null)
            fun isLoaded(id: Identifier) = loaded?.get(ResourceKey.create(registry, id))?.isPresent == true
            return resources.listResources(directory, ResourceParsing::isJson).keys
                .map { file -> idOf(file, directory) }
                .filterNot(::isLoaded)
                .flatMap { id -> listOf(id.toString(), id.path) }
        }

        /**
         * How a creature the world never offered arrives in one — one file, stacked so a pack may retune a
         * single creature without reprinting the rest.
         */
        private fun readSpawning(resources: ResourceManager, problems: MutableList<String>): Spawning {
            val file = Identifier.fromNamespaceAndPath(Constants.MOD_ID, Spawning.FILE)
            var standing = Spawning()
            for (layer in resources.getResourceStack(file)) {
                val read = ResourceParsing.parse(layer, file, Spawning.CODEC, problems) ?: continue
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
            val stacks = resources.listResourceStacks(DERIVATION_DIRECTORY, ResourceParsing::isJson)
            val rules = mutableMapOf<Aspect, Derivation>()
            for ((file, layers) in stacks) {
                if (ResourceParsing.nameUnder(file, DERIVATION_DIRECTORY) == MATERIALS) continue
                val aspect = aspectNamedBy(file, DERIVATION_DIRECTORY, problems) ?: continue
                for (layer in layers) {
                    val read = ResourceParsing.parse(layer, file, Derivation.CODEC, problems) ?: continue
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
            val stacks = resources.listResourceStacks(PRESET_TAGS_DIRECTORY, ResourceParsing::isJson)
            val bySlot = mutableMapOf<Aspect, MutableMap<String, PresetProfile>>()
            for ((file, layers) in stacks) {
                if (ResourceParsing.nameUnder(file, PRESET_TAGS_DIRECTORY) == MATERIALS) continue
                val aspect = aspectNamedBy(file, PRESET_TAGS_DIRECTORY, problems) ?: continue
                val merged = bySlot.getOrPut(aspect) { mutableMapOf() }
                for (layer in layers) {
                    val table = ResourceParsing.parse(layer, file, PresetTags.CODEC, problems) ?: continue
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

        private fun readOpposedTags(resources: ResourceManager, problems: MutableList<String>): List<OpposedTags> {
            val stacks = resources.listResourceStacks(OPPOSED_TAGS_DIRECTORY, ResourceParsing::isJson)
            return stacks.entries.sortedBy { it.key.toString() }.flatMap { (file, layers) ->
                layers.flatMap { layer -> ResourceParsing.parse(layer, file, OpposedTagsFile.CODEC, problems)?.pairs.orEmpty() }
            }
        }

        /** A file's id without its directory or suffix: `…/art/word/floating.json` → `…:floating`. */
        private fun idOf(file: Identifier, directory: String): Identifier =
            Identifier.fromNamespaceAndPath(file.namespace, ResourceParsing.nameUnder(file, directory))

        /** The aspect a per-aspect file under [directory] is named for, or null having said it names none. */
        private fun aspectNamedBy(file: Identifier, directory: String, problems: MutableList<String>): Aspect? {
            val key = ResourceParsing.nameUnder(file, directory)
            val aspect = Aspect.byPage(key)
            if (aspect == null) {
                problems += "$file names no aspect ('$key'); aspects are ${Aspect.entries.joinToString(" ") { it.page }}"
            }
            return aspect
        }
    }
}

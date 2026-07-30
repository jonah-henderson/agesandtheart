package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.AspectPreset
import co.voik.agesandtheart.age.word.grammar.GrammarWord
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.mojang.serialization.Codec
import com.mojang.serialization.JsonOps
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.RegistryAccess
import net.minecraft.resources.ResourceLocation
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
        // Tension the world absorbed is charged gently: a remark, not an accusation.
        private const val ORDINARY_SEVERITY = 1

        val CODEC: Codec<Antonym> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.STRING.fieldOf("first").forGetter(Antonym::first),
                Codec.STRING.fieldOf("second").forGetter(Antonym::second),
                Codec.INT.optionalFieldOf("severity", ORDINARY_SEVERITY).forGetter(Antonym::severity),
            ).apply(instance, ::Antonym)
        }
    }
}

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
    /** What could not be read, in the words a content author needs to hear. Empty in a healthy pack. */
    val problems: List<String>,
) {
    /** Distinct, because a derived word answers to both its bare path and its full id (see [load]). */
    val words: List<Word> get() = byName.values.distinct().sortedBy { it.name }

    /** The word a writer means by [name], or null if the corpus has never heard of it. */
    fun word(name: String): Word? = byName[name]

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
        return tagsBySlot[aspect]?.described.orEmpty().sorted().mapNotNull(aspect::presetFor)
    }

    /**
     * The presets in [aspect] this word would keep, at its tier's strictness. A word that **names** a
     * preset never searches, which is what keeps derived vocabulary free at resolve time (§8.2).
     */
    fun carriersOf(word: Word, aspect: Aspect): List<AspectPreset> {
        word.namedPreset(aspect)?.let { return listOf(it) }
        return candidatesFor(aspect).filter { word.accepts(tagsOf(it)) }
    }

    /**
     * Whether anything in [aspect] would actually *act* on [parameter], as opposed to declaring it — "has
     * this word anything to do here at all?" for a word that steers rather than chooses. A word may narrow
     * presets in one aspect and only turn a knob in another, and treating the second as unbacked condemns
     * a sentence that works.
     */
    fun turnsAKnob(aspect: Aspect, parameter: String): Boolean =
        candidatesFor(aspect).any { it.honoursParameterNamed(parameter) }

    /** Whether these two tags are known opposites, and how badly. */
    fun opposition(first: String, second: String): Antonym? = antonyms.firstOrNull { antonym ->
        (antonym.first == first && antonym.second == second) || (antonym.first == second && antonym.second == first)
    }

    companion object {
        /** Where a pack puts words. */
        const val WORD_DIRECTORY = "art/word"

        /** Where a pack puts the tags a aspect's presets carry, one file per aspect key. */
        const val PRESET_TAGS_DIRECTORY = "art/preset_tags"

        /** Where a pack puts antonym pages. */
        const val ANTONYM_DIRECTORY = "art/antonyms"

        /** Where a pack puts the structural words — one file per word, naming a production. */
        const val GRAMMAR_DIRECTORY = "art/grammar"

        private const val JSON_SUFFIX = ".json"

        /**
         * The corpus this server is currently running. Read fresh each time rather than cached: it happens
         * once when an Age is written, and a cache would need invalidating on `/reload`.
         */
        fun of(server: MinecraftServer): Vocabulary = load(server.resourceManager, server.registryAccess())

        /**
         * The corpus in [resources] — the whole of the loading, and usable offline.
         *
         * Authored and derived words meet in one map, so nothing downstream can tell them apart. Authored
         * wins every collision, and the derived word stays reachable by its full id — the reverse would
         * let a block from some mod quietly redefine a word of the Art.
         */
        fun load(resources: ResourceManager, registries: RegistryAccess? = null): Vocabulary {
            val problems = mutableListOf<String>()
            val authored = readWords(resources, problems)
            val tags = readPresetTags(resources, problems)
            val antonyms = readAntonyms(resources, problems)
            // Blocks are built-in and always available; biomes and structures are datapack content, so a
            // corpus read without a server has §8's material half and neither population. Absent rather
            // than wrong, which is what lets `VocabularyCheck` stay offline.
            val fromRegistries = registries?.let { DerivedWords.biomes(it) + DerivedWords.structures(it) }.orEmpty()
            val words = derived(DerivedWords.materials() + fromRegistries) + authored
            val structural = readGrammarWords(resources, problems)
            for (problem in problems) Constants.LOG.error("Art vocabulary: {}", problem)
            return Vocabulary(words, structural, tags, antonyms, problems)
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
            val ambiguous = words.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
            return buildMap {
                for (word in words) {
                    put(word.id.toString(), word)
                    if (word.name !in ambiguous) put(word.name, word)
                }
            }
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
        private fun idOf(file: ResourceLocation, directory: String): ResourceLocation =
            ResourceLocation.fromNamespaceAndPath(
                file.namespace,
                file.path.removePrefix("$directory/").removeSuffix(JSON_SUFFIX),
            )

        /**
         * One file through one codec, or null having said why. Failures are collected rather than thrown:
         * one malformed word must not cost a writer the rest, and a corpus that quietly lost a word is
         * exactly what §3.3 forbids.
         */
        private fun <T> parse(
            resource: Resource,
            file: ResourceLocation,
            codec: Codec<T>,
            problems: MutableList<String>,
        ): T? {
            val json: JsonElement = try {
                resource.openAsReader().use(JsonParser::parseReader)
            } catch (failure: Exception) {
                problems += "$file could not be read: ${failure.message}"
                return null
            }
            return codec.parse(JsonOps.INSTANCE, json)
                .resultOrPartial { error -> problems += "$file could not be understood: $error" }
                .orElse(null)
        }
    }
}

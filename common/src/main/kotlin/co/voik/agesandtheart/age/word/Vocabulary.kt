package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.slot.Slot
import co.voik.agesandtheart.age.slot.SlotPreset
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
 * **This is not the contradiction detector.** What actually breaks an Age is an *empty intersection*: two
 * words with no preset satisfying both, which the [Resolver] finds in the tag data itself. `floating` and
 * `flat` are opposites in no dictionary and nobody would think to list them here, yet no landform is
 * both — so a table-driven detector would silently drop a word the writer wrote. It fails the other way
 * too: were a preset to carry `lush 0.3` and `barren 0.3`, a table would charge for a contradiction the
 * world absorbed without complaint.
 *
 * What the table is for is **explaining** a tension and pricing it when it knows the pair. Gaps in it
 * therefore cost clarity, never correctness — which is what makes it affordable to author by hand.
 */
data class Antonym(val first: String, val second: String, val severity: Int) {
    companion object {
        // Tension the world absorbed is charged gently by default: "you wrote opposites and got both" is
        // a remark, not an accusation. A pair that deserves more says so in its own file.
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
 * presets carry, and the antonym table.
 *
 * **Loaded from datapacks** rather than compiled in, which is Jonah's call and the right one for this
 * layer: tag weights are the most taste-driven and most often retuned data in the mod, a pack that adds
 * a landform will want to tag it, and §8's vocabulary derived from Minecraft's own tags has to arrive at
 * runtime anyway — so authored and derived words meeting in one corpus is the seam that makes that
 * addition small. It sits inside the established "leaf content as data, composition as Kotlin" policy:
 * the resolver is composition and stays Kotlin.
 *
 * Files, all under `data/<namespace>/art/`:
 *
 * | path | holds |
 * |---|---|
 * | `word/<name>.json` | one word — its tier, the slots it may fill, its tag query |
 * | `preset_tags/<slot>.json` | the tags every preset in that slot carries |
 * | `antonyms/<page>.json` | pairs of tags that mean opposite things |
 *
 * One file per word so a pack can add or replace a single one; one file per *slot* of tags because tag
 * weights are only sensible read side by side, and stacked so a pack can retune a preset without
 * reprinting its neighbours.
 *
 * **A word that fails to load is reported, never silently absent** (§3.3's one hard requirement). Any
 * problem found while reading lands in [problems], which `/age words` prints and `:common:vocabularycheck`
 * fails the build over.
 */
data class Vocabulary(
    private val byName: Map<String, Word>,
    /** The structural words — `and`, `only`, `except` — keyed by what a writer says (§4.5). */
    private val structural: Map<String, GrammarWord>,
    private val tagsBySlot: Map<Slot, PresetTags>,
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
     * The structural word [name] spells, if it is one.
     *
     * Asked **before** [word], so a pack that also defines an ordinary word called `and` cannot quietly
     * make the conjunction unsayable — structure wins, because losing it costs the whole grammar where
     * losing one content word costs one word.
     */
    fun grammarWord(name: String): GrammarWord? = structural[name]

    /** What [preset] is like — an empty profile being a preset no word can currently reach. */
    fun profileOf(preset: SlotPreset): PresetProfile =
        tagsBySlot[preset.slot]?.of(preset) ?: PresetTags.EMPTY_PROFILE

    /** The tags [preset] carries — empty being a preset no word can currently reach. */
    fun tagsOf(preset: SlotPreset): Map<String, Double> = profileOf(preset).tags

    /** How willingly the Art reaches for [preset] when nothing asked for it. */
    fun readinessOf(preset: SlotPreset): Double =
        profileOf(preset).readiness ?: PresetProfile.ORDINARY_READINESS

    /** Every tag anything in the world carries, which bounds what any word can meaningfully ask for. */
    val carriedTags: Set<String> get() = tagsBySlot.values.flatMap { it.carried }.toSet()

    /**
     * **The curated pool** — everything a *vague* word may draw from in [slot] (design §8.2).
     *
     * For a closed slot this is its authored presets, as it always was. For an open one it is exactly what
     * `preset_tags/<slot>.json` has an entry for: that file already carried the tags and the readiness, and
     * it is now also the definition of the pool rather than an annotation on an enum that was.
     *
     * **The registry is deliberately not here**, and that is what makes §8.2 structural instead of a check
     * somebody has to remember. A derived word names one referent and arrives with its carrier already in
     * hand, so it never asks this question; vagueness draws only from the curated pool because vagueness
     * has nothing else to draw from. It also bounds the resolver's work by our curation rather than by the
     * size of the modpack.
     *
     * A pack that wants its own block reachable by *vague* words adds it to `preset_tags` with tags of its
     * own, which promotes it into this pool — the interop story is a file, not a feature.
     */
    fun candidatesFor(slot: Slot): List<SlotPreset> {
        if (!slot.open) return slot.authored
        // Sorted, because a draw is made by index and the file's own key order is not a thing anyone
        // should be able to change a world by editing. A closed slot gets the same guarantee from its
        // enum's declaration order.
        return tagsBySlot[slot]?.described.orEmpty().sorted().mapNotNull(slot::presetFor)
    }

    /**
     * The presets in [slot] this word would keep, at its own tier's strictness.
     *
     * A word that **names** a preset never searches: it has its answer already, so the curated pool is not
     * consulted and the registry never is (design §8.2). That is the whole of what keeps derived vocabulary
     * from costing anything at resolve time — a pack of forty thousand blocks makes this function no slower
     * than vanilla does.
     */
    fun carriersOf(word: Word, slot: Slot): List<SlotPreset> {
        word.namedPreset(slot)?.let { return listOf(it) }
        return candidatesFor(slot).filter { word.accepts(tagsOf(it)) }
    }

    /** Whether these two tags are known opposites, and how badly. */
    fun opposition(first: String, second: String): Antonym? = antonyms.firstOrNull { antonym ->
        (antonym.first == first && antonym.second == second) || (antonym.first == second && antonym.second == first)
    }

    companion object {
        /** Where a pack puts words. */
        const val WORD_DIRECTORY = "art/word"

        /** Where a pack puts the tags a slot's presets carry, one file per slot key. */
        const val PRESET_TAGS_DIRECTORY = "art/preset_tags"

        /** Where a pack puts antonym pages. */
        const val ANTONYM_DIRECTORY = "art/antonyms"

        /** Where a pack puts the structural words — one file per word, naming a production. */
        const val GRAMMAR_DIRECTORY = "art/grammar"

        private const val JSON_SUFFIX = ".json"

        /**
         * The corpus this server is currently running.
         *
         * Read from the resource manager each time rather than cached, deliberately: it happens once when
         * an Age is written, it is a few dozen small files, and a cache would need invalidating on
         * `/reload` — mutable state to save nothing measurable. If that ever stops being true, measure it
         * first (`/age bench` exists because of that rule).
         */
        fun of(server: MinecraftServer): Vocabulary = load(server.resourceManager, server.registryAccess())

        /**
         * The corpus in [resources] — the whole of the loading, and usable offline.
         *
         * Authored and derived words meet here in **one map**, which is the seam that made §8 an addition
         * rather than a mechanism: a derived word is an ordinary [Word] from a second source, and nothing
         * downstream can tell them apart or needs to.
         *
         * Authored wins every collision. A pack that deliberately writes a word called `water` meant it,
         * and the derived one is still reachable by its full id — where the reverse would let a block
         * added by some mod quietly redefine a word of the Art.
         */
        fun load(resources: ResourceManager, registries: RegistryAccess? = null): Vocabulary {
            val problems = mutableListOf<String>()
            val authored = readWords(resources, problems)
            val tags = readPresetTags(resources, problems)
            val antonyms = readAntonyms(resources, problems)
            // Fluids come from the built-in registries and so are always available; biomes are datapack
            // content, so a corpus read without a server has the medium half of §8 and not the dressing
            // half. Absent rather than wrong, which is what lets `:common:vocabularycheck` stay offline.
            val words = derived(DerivedWords.materials() + registries?.let(DerivedWords::biomes).orEmpty()) + authored
            val structural = readGrammarWords(resources, problems)
            for (problem in problems) Constants.LOG.error("Art vocabulary: {}", problem)
            return Vocabulary(words, structural, tags, antonyms, problems)
        }

        /**
         * Derived words under the names a writer may say them by: the bare registry path, and always the
         * full `namespace:path` (design §8.1.1).
         *
         * **A path two packs both ship stops being offered bare**, and neither of them gets it — offering
         * it to whichever loaded first would make what `creosote` means depend on mod load order, which is
         * the kind of bug nobody ever finds. The full id still reaches both, so nothing becomes
         * unreachable; it just has to be said unambiguously, which is exactly what the situation is.
         *
         * No problem is reported for a collision. It is not a content bug — two mods are allowed to both
         * have creosote — and it is not ours to fix.
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
                // Two packs both defining "floating" would otherwise leave which one a writer gets down
                // to map iteration order, so it is called out as the content collision it is.
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
         * rather than replacing a whole slot's table.
         */
        private fun readPresetTags(
            resources: ResourceManager,
            problems: MutableList<String>,
        ): Map<Slot, PresetTags> {
            val stacks = resources.listResourceStacks(PRESET_TAGS_DIRECTORY) { it.path.endsWith(JSON_SUFFIX) }
            val bySlot = mutableMapOf<Slot, MutableMap<String, PresetProfile>>()
            for ((file, layers) in stacks) {
                val slotKey = idOf(file, PRESET_TAGS_DIRECTORY).path
                val slot = Slot.entries.firstOrNull { it.key == slotKey }
                if (slot == null) {
                    problems += "$file names no slot ('$slotKey'); slots are ${Slot.entries.joinToString(" ") { it.key }}"
                    continue
                }
                val merged = bySlot.getOrPut(slot) { mutableMapOf() }
                for (layer in layers) {
                    val table = parse(layer, file, PresetTags.CODEC, problems) ?: continue
                    for (preset in table.described) {
                        // An open slot takes any well-formed registry id, since naming content this pack
                        // may not have is exactly what it is for — a `preset_tags` entry for a block from
                        // a mod that is not installed is a pack covering more ground than this instance
                        // runs, not a mistake. A closed slot still has to name one of its own.
                        if (slot.presetFor(preset) == null) {
                            problems += if (slot.open) {
                                "$file tags '$preset', which is not a `namespace:path` id"
                            } else {
                                "$file tags '$preset', which is no ${slot.key}"
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
         * One file through one codec, or null having said why.
         *
         * Every failure is collected rather than thrown: one malformed word must not cost a writer the
         * other twenty-nine, and a corpus that quietly lost a word is the exact failure §3.3 forbids.
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

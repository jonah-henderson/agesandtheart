package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.slot.Slot
import co.voik.agesandtheart.age.slot.SlotPreset
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.mojang.serialization.Codec
import com.mojang.serialization.JsonOps
import com.mojang.serialization.codecs.RecordCodecBuilder
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
    private val tagsBySlot: Map<Slot, PresetTags>,
    val antonyms: List<Antonym>,
    /** What could not be read, in the words a content author needs to hear. Empty in a healthy pack. */
    val problems: List<String>,
) {
    val words: List<Word> get() = byName.values.sortedBy { it.name }

    /** The word a writer means by [name], or null if the corpus has never heard of it. */
    fun word(name: String): Word? = byName[name]

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

    /** The presets in [slot] this word would keep, at its own tier's strictness. */
    fun carriersOf(word: Word, slot: Slot): List<SlotPreset> =
        slot.presets.filter { word.accepts(tagsOf(it)) }

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

        private const val JSON_SUFFIX = ".json"

        /**
         * The corpus this server is currently running.
         *
         * Read from the resource manager each time rather than cached, deliberately: it happens once when
         * an Age is written, it is a few dozen small files, and a cache would need invalidating on
         * `/reload` — mutable state to save nothing measurable. If that ever stops being true, measure it
         * first (`/age bench` exists because of that rule).
         */
        fun of(server: MinecraftServer): Vocabulary = load(server.resourceManager)

        /** The corpus in [resources] — the whole of the loading, and usable offline. */
        fun load(resources: ResourceManager): Vocabulary {
            val problems = mutableListOf<String>()
            val words = readWords(resources, problems)
            val tags = readPresetTags(resources, problems)
            val antonyms = readAntonyms(resources, problems)
            for (problem in problems) Constants.LOG.error("Art vocabulary: {}", problem)
            return Vocabulary(words, tags, antonyms, problems)
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
                        if (slot.presets.none { it.key == preset }) {
                            problems += "$file tags '$preset', which is no ${slot.key}"
                            continue
                        }
                        val standing = merged[preset]
                        merged[preset] = standing?.mergedWith(table.byKey(preset)) ?: table.byKey(preset)
                    }
                }
            }
            return bySlot.mapValues { (_, merged) -> PresetTags(merged.toMap()) }
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

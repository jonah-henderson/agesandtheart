package co.voik.agesandtheart.preview.authoring.words

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.reading.StructureWords
import co.voik.agesandtheart.age.reading.WordsInTheWorld
import co.voik.agesandtheart.age.reading.WorldMembership
import co.voik.agesandtheart.age.reading.Writing
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.words.Concept
import co.voik.agesandtheart.age.words.DerivedWords
import co.voik.agesandtheart.age.words.Kind
import co.voik.agesandtheart.age.words.Word
import co.voik.agesandtheart.age.words.Words
import co.voik.agesandtheart.location
import co.voik.agesandtheart.preview.authoring.ServerSnapshot
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import com.mojang.serialization.MapCodec
import net.minecraft.core.Registry
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import java.nio.file.Files
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

/**
 * The words as the source tree holds them — concepts, kinds, our words and every word derived over vanilla's worldgen —
 * over [vocabulary], the world they are read against: offline, and exact as far as it goes. [snapshot] is what a server
 * last said that offline cannot know — a tag only a bound registry grants, `ore` being the case — remembered so every
 * later run is offline again.
 */
class WordCorpus(
    val vocabulary: Vocabulary,
    val snapshot: ServerSnapshot? = null,
    private val derivedAlready: List<Derived>? = null,
) {
    /** A word the game gave us, and the registry it is an entry of — none for a design of ours. */
    data class Derived(val word: Word, val registry: ResourceKey<out Registry<*>>?)

    val concepts: List<Concept> by lazy { shipped(Words.CONCEPT_DIRECTORY, Concept::mapCodec) }

    val kinds: List<Kind> by lazy { shipped(Words.KIND_DIRECTORY, Kind::mapCodec) }

    /** Our words, each as its file holds it. */
    val written: List<Word> by lazy { shipped(Words.WORD_DIRECTORY, Word::mapCodec) }

    private val derivedFrom: List<Derived> by lazy { derivedAlready ?: derivedOver(vocabulary) }

    val derived: List<Word> by lazy { derivedFrom.map(Derived::word) }

    private val registryOf: Map<Identifier, ResourceKey<out Registry<*>>?> by lazy { derivedFrom.associate { it.word.id to it.registry } }

    /** The registry the derived word [word] is an entry of, or null for one of ours or a design. */
    fun registryOf(word: Word): ResourceKey<out Registry<*>>? = registryOf[word.id]

    /** Whether the game wrote [word] rather than a file of ours. */
    fun isDerived(word: Word): Boolean = word.id in registryOf

    /** How [word]'s listings know it — by name for ours, by id and its registry's tag directory for the game's. */
    fun listed(word: Word): WordListings.Listed {
        if (!isDerived(word)) return WordListings.Listed.authored(word.name)
        val directory = registryOf(word)?.let(Registries::tagsDirPath)
        return WordListings.Listed(word.id.toString(), word.name, word.id, directory)
    }

    /** These words read again from the files, as after one was saved, the game's own kept. */
    fun reread(): WordCorpus = WordCorpus(vocabulary, snapshot, derivedFrom)

    /**
     * This corpus with a snapshot taken from a server now and written down for every later run — the one way a screen
     * refreshes. [progress] is how the minutes of a server booting reach whoever asked.
     */
    fun withFreshSnapshot(progress: (ServerSnapshot.Progress) -> Unit): WordCorpus {
        val taken = ServerSnapshot.refresh(attach = null, vocabulary.tagsOnlyAServerGrants, say = progress)
        taken.write()
        return WordCorpus(vocabulary, taken, derivedFrom)
    }

    val membership: WorldMembership by lazy { WorldMembership(vocabulary) }

    /** The tags a word may speak of that offline cannot know, the snapshot's among them. */
    val tagsOnlyAServerGrants: Set<String> by lazy { vocabulary.tagsOnlyAServerGrants + snapshot?.serverOnly?.keys.orEmpty() }

    /** Every word, loaded and checked together. */
    val words: Words by lazy { with(emptyList()) }

    /** What in [words] the world cannot honour, asked once. */
    val world: WordsInTheWorld by lazy { WordsInTheWorld(words, vocabulary, membership, tagsOnlyAServerGrants) }

    /** The words with [extra] written beside ours, one of theirs replacing ours of the same name. */
    fun with(extra: List<Word>): Words {
        val ours = written.filter { word -> extra.none { it.name == word.name } }
        return Words.of(concepts, kinds, ours + extra, StructureWords.IDS, derived)
    }

    /** Rows of [words] written into Ages, nothing gated. */
    fun writing(words: Words): Writing = Writing(words, vocabulary, membership) { false }

    companion object {
        /** Everything under [directory] of our pack's data, each read by [codecFor], failing on a file that does not read. */
        fun <T> shipped(directory: String, codecFor: (Identifier) -> MapCodec<T>): List<T> =
            MinecraftRegistries.resourceRoot().resolve("data/agesandtheart/$directory").listDirectoryEntries()
                .filter { it.extension == "json" }
                .map { file ->
                    codecFor(file.nameWithoutExtension.location()).codec().parse(JsonOps.INSTANCE, JsonParser.parseString(Files.readString(file)))
                        .getOrThrow { IllegalStateException("${file.fileName} does not read: $it") }
                }

        /**
         * The corpus, stood up. Slow once — Minecraft's registries are the only expensive thing here — and then every
         * question is a map lookup.
         */
        fun load(): WordCorpus {
            // Standing the registries up redirects System.out into log4j, which reprints every line with a timestamp —
            // fatal to a frame that redraws in place — so the real streams are taken back.
            val console = System.out
            val complaints = System.err
            MinecraftRegistries.ensureStoodUp()
            System.setOut(console)
            System.setErr(complaints)
            return WordCorpus(Vocabulary.load(MinecraftRegistries.shippedData(), MinecraftRegistries.worldgen), ServerSnapshot.read())
        }

        /** The word every block, biome, feature, structure, creature and design of ours is given, over vanilla's worldgen. */
        fun derivedOver(vocabulary: Vocabulary): List<Derived> {
            val worldgen = MinecraftRegistries.worldgen
            val features = vocabulary.candidatesFor(Aspect.FEATURES).associateBy { it.key }
            fun tagsOf(feature: String) = features[feature]?.let(vocabulary::tagsOf).orEmpty()
            fun from(registry: ResourceKey<out Registry<*>>?, words: List<Word>) = words.map { Derived(it, registry) }
            return from(Registries.BLOCK, DerivedWords.blocks()) + from(Registries.BIOME, DerivedWords.biomes(worldgen)) +
                from(Registries.PLACED_FEATURE, DerivedWords.features(worldgen, ::tagsOf)) +
                from(Registries.STRUCTURE_SET, DerivedWords.structures(worldgen)) +
                from(Registries.ENTITY_TYPE, DerivedWords.creatures(vocabulary.spawning.writable)) + from(null, DerivedWords.designs())
        }
    }
}

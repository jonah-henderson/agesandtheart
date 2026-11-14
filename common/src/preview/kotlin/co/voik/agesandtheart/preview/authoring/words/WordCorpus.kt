package co.voik.agesandtheart.preview.authoring.words

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.reading.StructureWords
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
import net.minecraft.resources.Identifier
import java.nio.file.Files
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

/**
 * The words as the source tree holds them — concepts, kinds, our words and every word derived over vanilla's worldgen —
 * over [vocabulary], the world they are read against. [snapshot] is what a server last said that offline cannot know.
 */
class WordCorpus(val vocabulary: Vocabulary, val snapshot: ServerSnapshot? = null) {

    val concepts: List<Concept> by lazy { shipped(Words.CONCEPT_DIRECTORY, Concept::mapCodec) }

    val kinds: List<Kind> by lazy { shipped(Words.KIND_DIRECTORY, Kind::mapCodec) }

    /** Our words, each as its file holds it. */
    val written: List<Word> by lazy { shipped(Words.WORD_DIRECTORY, Word::mapCodec) }

    val derived: List<Word> by lazy { derivedOver(vocabulary) }

    val membership: WorldMembership by lazy { WorldMembership(vocabulary) }

    /** The tags a word may speak of that offline cannot know, the snapshot's among them. */
    val tagsOnlyAServerGrants: Set<String> by lazy { vocabulary.tagsOnlyAServerGrants + snapshot?.serverOnly?.keys.orEmpty() }

    /** Every word, loaded and checked together. */
    val words: Words by lazy { with(emptyList()) }

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

        /** The word every block, biome, feature, structure, creature and design of ours is given, over vanilla's worldgen. */
        fun derivedOver(vocabulary: Vocabulary): List<Word> {
            val worldgen = MinecraftRegistries.worldgen
            val features = vocabulary.candidatesFor(Aspect.FEATURES).associateBy { it.key }
            fun tagsOf(feature: String) = features[feature]?.let(vocabulary::tagsOf).orEmpty()
            return DerivedWords.blocks() + DerivedWords.biomes(worldgen) + DerivedWords.features(worldgen, ::tagsOf) +
                DerivedWords.structures(worldgen) + DerivedWords.creatures(vocabulary.spawning.writable) + DerivedWords.designs()
        }
    }
}

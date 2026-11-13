package co.voik.agesandtheart.age.words

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.reading.StructureWords
import co.voik.agesandtheart.location
import com.google.gson.JsonParser
import com.mojang.serialization.Codec
import com.mojang.serialization.JsonOps
import com.mojang.serialization.MapCodec
import net.minecraft.resources.Identifier
import java.nio.file.Files
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

/**
 * The new model's words as we ship them — concepts, kinds, the words under `art/words/` and every derived word over
 * vanilla's worldgen — read from the source tree, with a spec's own words beside them where it needs some.
 */
object ShippedWords {
    fun <T> shipped(directory: String, codecFor: (Identifier) -> MapCodec<T>): List<T> =
        MinecraftRegistries.resourceRoot().resolve("data/agesandtheart/$directory").listDirectoryEntries()
            .filter { it.extension == "json" }
            .map { file ->
                codecFor(file.nameWithoutExtension.location()).codec().parse(JsonOps.INSTANCE, JsonParser.parseString(Files.readString(file)))
                    .getOrThrow { IllegalStateException("${file.fileName} does not read: $it") }
            }

    fun <T> read(codec: Codec<T>, json: String): T = codec.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow()

    val concepts: List<Concept> by lazy { shipped(Words.CONCEPT_DIRECTORY, Concept::mapCodec) }

    val kinds: List<Kind> by lazy { shipped(Words.KIND_DIRECTORY, Kind::mapCodec) }

    val written: List<Word> by lazy { shipped(Words.WORD_DIRECTORY, Word::mapCodec) }

    val derived: List<Word> by lazy {
        val worldgen = MinecraftRegistries.worldgen
        DerivedWords.blocks() + DerivedWords.biomes(worldgen) + DerivedWords.features(worldgen) + DerivedWords.structures(worldgen) +
            DerivedWords.creatures(ShippedCorpus.vocabulary.spawning.writable) + DerivedWords.designs()
    }

    /** Every word we ship, with [extra] written beside them, a name of theirs replacing ours. */
    fun with(extra: Map<String, String> = emptyMap()): Words {
        val added = extra.map { (name, json) -> read(Word.mapCodec(name.location()).codec(), json) }
        val ours = written.filter { word -> extra.keys.none { it == word.name } }
        return Words.of(concepts, kinds, ours + added, StructureWords.IDS, derived)
    }
}

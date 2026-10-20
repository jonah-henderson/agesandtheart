package co.voik.agesandtheart.age.language

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Taggable
import co.voik.agesandtheart.location
import co.voik.agesandtheart.preview.meanings.WordConversion
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

/**
 * Every shipped word on meanings, over vanilla's worldgen: the authored ones converted as the cutover will convert
 * them, and the derived ones as the pack would derive them. Needs `@Tags(NEEDS_REGISTRIES)`.
 */
object ShippedLexicon {
    private val ART = Path.of("src/main/resources/data/agesandtheart/art")

    private val TAGS = object : MemberTags {
        override fun availableToBroadWordsIn(aspect: Aspect): List<Taggable> =
            ShippedCorpus.vocabulary.availableToBroadWordsIn(aspect)

        override fun tagsOf(member: Taggable): Map<String, Double> = ShippedCorpus.vocabulary.tagsOf(member)
    }

    val lexicon: Lexicon by lazy {
        val registries = MinecraftRegistries.worldgen
        val derived = DerivedWords.blocks() + DerivedWords.spawns() + DerivedWords.biomes(registries) +
            DerivedWords.features(registries) + DerivedWords.structures(registries) + DerivedWords.designs()
        Lexicon(derived + DerivedWords.aspectPages() + authored() + quantifiers(), structural(), TAGS, registries)
    }

    /** A book of [pages] read at [seed], which must read. */
    fun read(pages: String, seed: Long = 0L): Reading =
        requireNotNull(Reader.read(lexicon, pages.split(" "), seed)) { "'$pages' does not read" }

    private fun authored(): List<Word> = ART.resolve("word").listDirectoryEntries("*.json").map { file ->
        val name = file.nameWithoutExtension
        val converted = WordConversion.converted(name, JsonParser.parseString(file.readText()).asJsonObject)
        WordFormat.mapCodec(name.location()).codec().parse(JsonOps.INSTANCE, converted).orThrow
    }

    private fun quantifiers(): List<Word> = grammar().mapNotNull { (name, json) ->
        val converted = WordConversion.quantifier(json) ?: return@mapNotNull null
        WordFormat.mapCodec(name.location()).codec().parse(JsonOps.INSTANCE, converted).orThrow
    }

    private fun structural(): Map<String, Structure> = grammar().mapNotNull { (name, json) ->
        json.get("production")?.asString?.let(Structure::named)?.let { name to it }
    }.toMap()

    private fun grammar() = ART.resolve("grammar").listDirectoryEntries("*.json").map { file ->
        file.nameWithoutExtension to JsonParser.parseString(file.readText()).asJsonObject
    }
}

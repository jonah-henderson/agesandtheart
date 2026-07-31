package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import com.google.gson.JsonParser
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import java.io.File

/**
 * Whether the hand-curated ink tags name things that exist.
 *
 * A tag entry naming no block is not a quiet no-op — the tag fails to load and every word in it silently
 * drops to common ink, which is the treasure-Age brake coming off without anyone noticing. Blocks are
 * built-in so they can be checked offline; biomes and structure sets are datapack content and are only
 * resolvable with a server.
 */
@Tags(NEEDS_REGISTRIES)
class InkTagCheck : FunSpec({

    val tagRoot = File("src/main/resources/data/agesandtheart/tags")

    fun valuesIn(file: File): List<String> =
        JsonParser.parseString(file.readText()).asJsonObject
            .getAsJsonArray("values").map { it.asString }

    test("every block named by an ink tag exists") {
        MinecraftRegistries.ensureStoodUp()
        val files = File(tagRoot, "block").listFiles { f -> f.name.startsWith("requires_") }.orEmpty()
        check(files.isNotEmpty()) { "No ink tags found under ${tagRoot.absolutePath}/block" }

        val missing = files.flatMap { file ->
            valuesIn(file).filterNot { BuiltInRegistries.BLOCK.containsKey(Identifier.parse(it)) }
                .map { "${file.name}: $it" }
        }
        check(missing.isEmpty()) { "Ink tags name blocks that do not exist:\n  ${missing.joinToString("\n  ")}" }
    }

    /** The two tiers must not overlap, or which ink a thing needs depends on map iteration order. */
    test("no block demands two different inks") {
        MinecraftRegistries.ensureStoodUp()
        val byTier = File(tagRoot, "block").listFiles { f -> f.name.startsWith("requires_") }.orEmpty()
            .associate { it.name to valuesIn(it).toSet() }
        val overlap = byTier.values.reduceOrNull { a, b -> a intersect b }.orEmpty()
        check(overlap.isEmpty()) { "Blocks appear in more than one ink tag: $overlap" }
    }

    /** Authored words are listed by name, so a typo there is a word that silently stays cheap. */
    test("every authored word listed for an ink tier is a real word") {
        val vocabulary = Vocabulary.load(MinecraftRegistries.shippedData())
        val inkDirectory = File("src/main/resources/data/agesandtheart/art/ink")
        val listed = inkDirectory.listFiles { f -> f.extension == "json" }.orEmpty()
            .flatMap { file ->
                JsonParser.parseString(file.readText()).asJsonObject
                    .getAsJsonArray("words").map { file.name to it.asString }
            }
        val unknown = listed.filter { (_, word) -> vocabulary.word(word) == null }
        check(unknown.isEmpty()) { "art/ink lists words the corpus does not have: $unknown" }
    }
})

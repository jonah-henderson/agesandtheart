package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.Registry
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import java.io.File

/**
 * **Whether our worldgen tags name things that exist** — biomes and structure sets, which are datapack
 * content rather than built-in registries.
 *
 * **This is a crash, not a warning, and that is why it earns a check of its own.** A tag entry is a registry
 * *reference*: naming something the pack does not ship leaves the entry unbound, and `MappedRegistry.freeze`
 * throws `Unbound values in registry` at world load. Nothing before that point complains — the file parses,
 * the build passes, the suite passes, and the game dies on the loading screen.
 *
 * It happened on 2026-09-11: `#agesandtheart:no_abyss` was written naming `agesandtheart:great_halls` where
 * the biome this pack ships is `great_hall`, singular. One letter, and no world would open.
 *
 * [InkTagCheck] says of this exact case that "biomes and structure sets are datapack content and are only
 * resolvable with a server". That is true of the *registry* and not of the question: vanilla's are in the
 * offline worldgen lookup and ours are files on disk, so both halves are answerable here after all.
 */
@Tags(NEEDS_REGISTRIES)
class WorldgenTagCheck : FunSpec({

    test("every biome named by one of our tags exists") {
        val missing = danglingIn("biome", Registries.BIOME)
        check(missing.isEmpty()) {
            "Biome tags name biomes this pack does not ship, which fails the registry freeze at world " +
                "load:\n  ${missing.joinToString("\n  ")}"
        }
    }

    test("every structure set named by one of our tags exists") {
        val missing = danglingIn("structure_set", Registries.STRUCTURE_SET)
        check(missing.isEmpty()) {
            "Structure set tags name sets this pack does not ship, which fails the registry freeze at " +
                "world load:\n  ${missing.joinToString("\n  ")}"
        }
    }

    /** And that the check has something to chew on, so a moved directory cannot make it pass on nothing. */
    test("there are worldgen tags to check in the first place") {
        val found = listOf("biome", "structure_set").sumOf { filesUnder(it).size }
        check(found > 0) { "no worldgen tag files under ${TAG_ROOT.absolutePath}" }
        println("  $found worldgen tag files checked")
    }
}) {
    private companion object {

        private val TAG_ROOT = File("src/main/resources/data/agesandtheart/tags/worldgen")
        private val SHIPPED = File("src/main/resources/data/agesandtheart/worldgen")

        private const val OURS = "agesandtheart"

        private fun filesUnder(kind: String): List<File> =
            File(TAG_ROOT, kind).listFiles { file -> file.name.endsWith(".json") }.orEmpty().toList()

        /**
         * Every id named by a tag of this [kind] that nothing answers for, as `<file>: <id>`.
         *
         * **A reference to another tag is skipped** — `#namespace:name` names a tag rather than an entry,
         * and an unbound *tag* is a load-time complaint rather than a freeze failure.
         */
        private fun <T : Any> danglingIn(kind: String, registry: ResourceKey<Registry<T>>): List<String> {
            val theirs = MinecraftRegistries.worldgen.lookupOrThrow(registry)
            return filesUnder(kind).flatMap { file ->
                namesIn(file)
                    .filterNot { named -> named.startsWith(TAG_MARK) }
                    .filterNot { named ->
                        exists(named, kind) { id -> theirs.get(ResourceKey.create(registry, id)).isPresent }
                    }
                    .map { "${file.name}: $it" }
            }
        }

        private const val TAG_MARK = "#"

        /**
         * Ours are asked of the resource tree and everyone else's of the offline registry, which is the
         * same bargain [InkTagCheck] strikes for blocks and for the same reason: a datapack file is what
         * every real one of ours has, and it is what a typo does not.
         */
        private fun exists(named: String, kind: String, inTheirs: (Identifier) -> Boolean): Boolean {
            val id = Identifier.tryParse(named) ?: return false
            if (id.namespace != OURS) return inTheirs(id)
            return File(SHIPPED, "$kind/${id.path}.json").isFile
        }

        /**
         * The ids a tag file names, taking both spellings vanilla allows: a bare string, and an object with
         * an `id` (which is how an entry says it is optional).
         */
        private fun namesIn(file: File): List<String> {
            val values = JsonParser.parseString(file.readText()).asJsonObject["values"] as? JsonArray
                ?: return emptyList()
            return values.mapNotNull { entry ->
                when {
                    entry.isJsonPrimitive -> entry.asString
                    // An optional entry may name something absent on purpose, so it is not a dangling id.
                    entry.isJsonObject && entry.asJsonObject["required"]?.asBoolean == false -> null
                    entry.isJsonObject -> entry.asJsonObject["id"]?.asString
                    else -> null
                }
            }
        }
    }
}

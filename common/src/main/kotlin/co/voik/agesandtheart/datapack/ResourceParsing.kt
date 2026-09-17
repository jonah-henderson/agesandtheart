package co.voik.agesandtheart.datapack

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.mojang.serialization.Codec
import com.mojang.serialization.JsonOps
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.Resource

/** Reading datapack files through codecs, collecting failures rather than throwing. */
object ResourceParsing {
    /** The one kind of file a datapack directory of ours is read for. */
    const val JSON_SUFFIX = ".json"

    /** Whether [file] is one of them — the filter every directory listing takes. */
    fun isJson(file: Identifier): Boolean = file.path.endsWith(JSON_SUFFIX)

    /** What a file under [directory] is called: `art/word/floating.json` is `floating`. */
    fun nameUnder(file: Identifier, directory: String): String =
        file.path.removePrefix("$directory/").removeSuffix(JSON_SUFFIX)

    /**
     * One file through one codec, or null having said why. Failures are collected because one malformed
     * word must not cost a writer the rest, and a corpus that quietly lost a word is exactly what the
     * design forbids (§3.3).
     */
    fun <T> parse(
        resource: Resource,
        file: Identifier,
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

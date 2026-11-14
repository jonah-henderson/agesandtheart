package co.voik.agesandtheart.preview.authoring.words

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.age.words.Word
import co.voik.agesandtheart.age.words.Words
import co.voik.agesandtheart.location
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import java.io.File

/**
 * Where the pack's words live on disk, and how one is read and written back. **The codec owns the word and this owns the
 * file**: the word's codec ignores `_comment`, so it is carried beside the word and laid first, and every object is laid
 * out in one order, so that opening a word and saving it moves nothing.
 */
object WordFile {

    private val GSON = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()

    private const val JSON_SUFFIX = ".json"
    private const val COMMENT = "_comment"

    private val LEGAL_NAME = Regex("[a-z0-9/._-]+")

    private val directory: File by lazy {
        val art = MinecraftRegistries.resourceRoot().toFile().resolve("data/${Constants.MOD_ID}/${Words.WORD_DIRECTORY}")
        art.also { check(it.isDirectory) { "no ${Words.WORD_DIRECTORY} under ${it.absolutePath}" } }
    }

    /** Every word this pack authored, by name. Derived words have no file and are not here. */
    fun authoredNames(): List<String> =
        directory.listFiles { file -> file.name.endsWith(JSON_SUFFIX) }.orEmpty().map { it.name.removeSuffix(JSON_SUFFIX) }.sorted()

    fun fileFor(name: String): File = directory.resolve("$name$JSON_SUFFIX")

    fun exists(name: String): Boolean = fileFor(name).isFile

    /** Whether [name] could be a word's file name — the rule [write] refuses on, asked in advance. */
    fun couldBeAName(name: String): Boolean = name.matches(LEGAL_NAME)

    /** The word in [name]'s file, or why it will not read. */
    fun read(name: String): Result<Candidate> = runCatching {
        val json = JsonParser.parseString(fileFor(name).readText()).asJsonObject
        val comment = json.get(COMMENT)?.let { said -> if (said.isJsonArray) said.asJsonArray.map { it.asString } else listOf(said.asString) }
        val word = Word.mapCodec(name.location()).codec().parse(JsonOps.INSTANCE, json)
            .getOrThrow { problem -> IllegalArgumentException("'$name' does not read: $problem") }
        Candidate(word, comment.orEmpty())
    }

    /** What [candidate]'s file says: the comment first, then the word as its codec spells it, laid out in order. */
    fun textOf(candidate: Candidate): String {
        val encoded = Word.mapCodec(candidate.word.id).codec().encodeStart(JsonOps.INSTANCE, candidate.word)
            .getOrThrow { problem -> IllegalArgumentException("'${candidate.name}' does not write: $problem") }
            .asJsonObject
        val laid = JsonObject()
        if (candidate.comment.isNotEmpty()) laid.add(COMMENT, JsonArray().apply { candidate.comment.forEach(::add) })
        laidOut(encoded, WORD_ORDER).entrySet().forEach { (key, value) -> laid.add(key, value) }
        return GSON.toJson(laid) + "\n"
    }

    /** [candidate] written to its file, refusing a name no file may have. */
    fun write(candidate: Candidate) {
        require(couldBeAName(candidate.name)) { "'${candidate.name}' cannot be a word's file name" }
        fileFor(candidate.name).writeText(textOf(candidate))
    }

    /** [json]'s keys in [order], then any others as they came; its senses, offers and acceptances laid out in theirs. */
    private fun laidOut(json: JsonObject, order: List<String>): JsonObject {
        val laid = JsonObject()
        val keys = order.filter(json::has) + json.keySet().filterNot(order::contains)
        for (key in keys) laid.add(key, laidOutWithin(key, json.get(key)))
        return laid
    }

    private fun laidOutWithin(key: String, value: JsonElement): JsonElement {
        val order = ORDER_WITHIN[key] ?: return value
        if (!value.isJsonArray) return value
        return JsonArray().apply { value.asJsonArray.forEach { add(if (it.isJsonObject) laidOut(it.asJsonObject, order) else it) } }
    }

    /** A word and each of its senses, top to bottom: how it is said, what it is, what it means, offers and takes. */
    private val WORD_ORDER = listOf(
        "speech", "behaviour", "terminal", "part", "kind", "price", "means", "offers", "accepts", "consumes_whole_page", "default", "senses",
    )

    private val OFFER_ORDER = listOf("concept", "value", "for", "chance", "chance_from_whole_age", "means")

    private val ACCEPTANCE_ORDER = listOf(
        "concept", "only", "becomes", "folds", "renames", "scales", "drops", "chance_from_whole_age", "can_resolve_dangling_offers",
    )

    private val ORDER_WITHIN = mapOf("senses" to WORD_ORDER, "offers" to OFFER_ORDER, "accepts" to ACCEPTANCE_ORDER)
}

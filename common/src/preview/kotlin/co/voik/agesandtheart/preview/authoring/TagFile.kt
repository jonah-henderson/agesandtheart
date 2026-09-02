package co.voik.agesandtheart.preview.authoring

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * The authored half of the tag layer on disk — `art/preset_tags/<aspect page>.json`.
 *
 * **The overlay, not the answer.** What a preset actually carries is the derivation with this laid over
 * it, so everything written here is an exception to a rule rather than a statement of fact. [Authored] is
 * what one file says; what a preset ends up with is [TagLayer]'s question.
 *
 * Edits are made **to the parsed JSON and written back**, never encoded from a value: an entry may carry
 * a `_comment` the codec does not read, and `phenomena.json` does.
 */
object TagFile {

    private val GSON = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()

    private const val JSON_SUFFIX = ".json"

    private const val TAGS = "tags"
    private const val DROP = "drop"
    private const val REPLACE = "replace"
    private const val READINESS = "readiness"

    private val directory: File get() = WordFile.art.resolve("preset_tags")

    /** One preset's entry, as its file states it. */
    data class Authored(
        val tags: Map<String, Double> = emptyMap(),
        val dropped: Set<String> = emptySet(),
        val replaces: Boolean = false,
        val readiness: Double? = null,
    )

    /** Which aspects have a table at all — file names, which are aspect *pages*. */
    fun pages(): List<String> =
        directory.listFiles { file -> file.name.endsWith(JSON_SUFFIX) }.orEmpty()
            .map { it.name.removeSuffix(JSON_SUFFIX) }
            .sorted()

    fun fileFor(page: String): File = directory.resolve("$page$JSON_SUFFIX")

    private fun read(page: String): JsonObject {
        val file = fileFor(page)
        if (!file.isFile) return JsonObject()
        return runCatching { JsonParser.parseString(file.readText()).asJsonObject }.getOrDefault(JsonObject())
    }

    private fun write(page: String, json: JsonObject) {
        val file = fileFor(page)
        file.parentFile.mkdirs()
        file.writeText(GSON.toJson(json) + "\n")
    }

    /** What [page]'s table says, preset by preset. */
    fun authored(page: String): Map<String, Authored> = read(page).entrySet().associate { (preset, entry) ->
        val body = entry.asJsonObject
        preset to Authored(
            tags = body.getAsJsonObject(TAGS)?.entrySet()
                ?.associate { (tag, weight) -> tag to weight.asDouble }.orEmpty(),
            dropped = body.getAsJsonArray(DROP)?.map { it.asString }?.toSet().orEmpty(),
            replaces = body.get(REPLACE)?.asBoolean ?: false,
            readiness = body.get(READINESS)?.asDouble,
        )
    }

    /**
     * [preset] carries [tag] at [weight] in [page]'s table, or carries no authored weight at all.
     *
     * **A weight of null is not zero.** Removing the entry lets whatever the derivation said come back,
     * which is the difference between "we have nothing to add" and "not this one" — the second is
     * [setDropped].
     */
    fun setWeight(page: String, preset: String, tag: String, weight: Double?) =
        write(page, withWeight(read(page), preset, tag, weight))

    /** Whether [preset] takes [tag] back off whatever the derivation gave it. */
    fun setDropped(page: String, preset: String, tag: String, dropped: Boolean) =
        write(page, withDropped(read(page), preset, tag, dropped))

    /**
     * The edits themselves, **as transformations of the table rather than of the file.**
     *
     * Pure so they can be checked: every one of them has an inverse a person will expect to work — set a
     * weight and clear it, drop a tag and restore it — and a check that had to write into the shipped
     * corpus to prove that could not be run.
     */
    fun withWeight(table: JsonObject, preset: String, tag: String, weight: Double?): JsonObject {
        val entry = table.getAsJsonObject(preset) ?: JsonObject().also { table.add(preset, it) }
        val tags = entry.getAsJsonObject(TAGS) ?: JsonObject().also { entry.add(TAGS, it) }
        if (weight == null) tags.remove(tag) else tags.addProperty(tag, rounded(weight))
        if (tags.size() == 0) entry.remove(TAGS)
        return tidied(table, preset, entry)
    }

    fun withDropped(table: JsonObject, preset: String, tag: String, dropped: Boolean): JsonObject {
        val entry = table.getAsJsonObject(preset) ?: JsonObject().also { table.add(preset, it) }
        val standing = entry.getAsJsonArray(DROP)?.map { it.asString }.orEmpty()
        val wanted = if (dropped) (standing + tag).distinct().sorted() else standing - tag
        if (wanted.isEmpty()) entry.remove(DROP) else entry.add(DROP, JsonArray().apply { wanted.forEach(::add) })
        return tidied(table, preset, entry)
    }

    /** An entry that now says nothing is taken out, so an edit and its undo leave the file as it was. */
    private fun tidied(table: JsonObject, preset: String, entry: JsonObject): JsonObject {
        val saysNothing = entry.keySet().none { it in setOf(TAGS, DROP, REPLACE, READINESS) }
        if (saysNothing) table.remove(preset)
        return table
    }

    /** Two decimal places, which is what every weight in the corpus already has. */
    private fun rounded(weight: Double) = Math.round(weight * HUNDREDTHS) / HUNDREDTHS

    private const val HUNDREDTHS = 100.0

    /**
     * A tag renamed **everywhere it is written down** — the tables that carry it, the words that ask for
     * it, and the antonym table that opposes it.
     *
     * This is the operation a text editor is worst at and the pass keeps needing: `brilliant` became
     * `bright` and `sweeping` folded into `colossal`, and each one is three kinds of file. A rename that
     * reached only the carriers would leave every word asking for a tag nothing carried any more —
     * which is silent, because a query for a tag nobody has is legal and simply finds nothing.
     */
    fun renameTag(from: String, to: String): List<String> {
        if (from == to || from.isEmpty() || to.isEmpty()) return emptyList()
        return buildList {
            addAll(renameInTables(from, to))
            addAll(renameInWords(from, to))
            addAll(renameInAntonyms(from, to))
        }
    }

    private fun renameInTables(from: String, to: String): List<String> = pages().mapNotNull { page ->
        val json = read(page)
        if (!renameInTable(json, from, to)) return@mapNotNull null
        write(page, json)
        "preset_tags/$page"
    }

    /** True where [table] mentioned [from] and now says [to] instead. */
    fun renameInTable(table: JsonObject, from: String, to: String): Boolean {
        var moved = false
        for ((_, entry) in table.entrySet()) {
            val body = entry.asJsonObject
            body.getAsJsonObject(TAGS)?.let { tags ->
                if (tags.has(from)) {
                    // Renamed onto an existing weight takes the stronger claim, the way two derivation
                    // rules calling one thing the same name do.
                    val weight = maxOf(tags.get(from).asDouble, tags.get(to)?.asDouble ?: 0.0)
                    tags.remove(from)
                    tags.addProperty(to, weight)
                    moved = true
                }
            }
            body.getAsJsonArray(DROP)?.let { drop ->
                val standing = drop.map { it.asString }
                if (from in standing) {
                    val wanted = (standing - from + to).distinct().sorted()
                    body.add(DROP, JsonArray().apply { wanted.forEach(::add) })
                    moved = true
                }
            }
        }
        return moved
    }

    /** Every place a word file spells a tag: `query`, `queries.<aspect>`, and the same two under `requests`. */
    private fun renameInWords(from: String, to: String): List<String> =
        WordFile.authoredNames().mapNotNull { name ->
            val file = WordFile.fileFor(name)
            val json = runCatching { JsonParser.parseString(file.readText()).asJsonObject }.getOrNull()
                ?: return@mapNotNull null
            if (!renameInWord(json, from, to)) return@mapNotNull null
            file.writeText(GSON.toJson(json) + "\n")
            "word/$name"
        }

    /** True where this word file mentioned [from] anywhere it can spell a tag, and now says [to]. */
    fun renameInWord(word: JsonObject, from: String, to: String): Boolean =
        listOfNotNull(word, word.getAsJsonObject("requests")).count { holder ->
            val here = renameKey(holder.getAsJsonObject("query"), from, to)
            val perAspect = holder.getAsJsonObject("queries")?.entrySet().orEmpty()
                .count { (_, one) -> renameKey(one.asJsonObject, from, to) }
            here || perAspect > 0
        } > 0

    /** True where the key was there and moved. Weights are signed, so the sign travels with it. */
    private fun renameKey(holder: JsonObject?, from: String, to: String): Boolean {
        if (holder?.has(from) != true) return false
        val weight = holder.get(from).asDouble
        holder.remove(from)
        holder.addProperty(to, weight)
        return true
    }

    private fun renameInAntonyms(from: String, to: String): List<String> {
        val directory = WordFile.art.resolve("antonyms")
        return directory.listFiles { file -> file.name.endsWith(JSON_SUFFIX) }.orEmpty().mapNotNull { file ->
            val json = runCatching { JsonParser.parseString(file.readText()).asJsonObject }.getOrNull()
                ?: return@mapNotNull null
            var moved = false
            for (pair in json.getAsJsonArray("pairs").orEmpty()) {
                val body = pair.asJsonObject
                for (side in listOf("first", "second")) {
                    if (body.get(side)?.asString != from) continue
                    body.addProperty(side, to)
                    moved = true
                }
            }
            if (!moved) return@mapNotNull null
            file.writeText(GSON.toJson(json) + "\n")
            "antonyms/${file.name.removeSuffix(JSON_SUFFIX)}"
        }
    }

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray()
}

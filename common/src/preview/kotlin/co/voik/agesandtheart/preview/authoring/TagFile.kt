package co.voik.agesandtheart.preview.authoring

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import co.voik.agesandtheart.age.word.Word
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
    private const val AVAILABLE_TO_BROAD_WORDS = "available_to_broad_words"

    /** The three fields of a word file a tag can be written in — see [spellings]. */
    private const val RESTRICTS = "restricts"
    private const val BIASES = "biases"
    private const val EXCLUDES = "excludes"

    private val directory: File get() = WordFile.art.resolve("preset_tags")

    /** One preset's entry, as its file states it. */
    data class Authored(
        val tags: Map<String, Double> = emptyMap(),
        val dropped: Set<String> = emptySet(),
        val replaces: Boolean = false,
        val readiness: Double? = null,
        val availableToBroadWords: Boolean = true,
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
            availableToBroadWords = body.get(AVAILABLE_TO_BROAD_WORDS)?.asBoolean ?: true,
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

    /** Whether a vague word may land on [preset], or only a word naming it — `available_to_broad_words`. */
    fun setAvailableToBroadWords(page: String, preset: String, available: Boolean) =
        write(page, withAvailableToBroadWords(read(page), preset, available))

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

    /** True is the default and so is written as nothing, which keeps the undo of `false` exact. */
    fun withAvailableToBroadWords(table: JsonObject, preset: String, available: Boolean): JsonObject {
        val entry = table.getAsJsonObject(preset) ?: JsonObject().also { table.add(preset, it) }
        if (available) entry.remove(AVAILABLE_TO_BROAD_WORDS) else entry.addProperty(AVAILABLE_TO_BROAD_WORDS, false)
        return tidied(table, preset, entry)
    }

    /**
     * An entry that now says nothing is taken out, so an edit and its undo leave the file as it was.
     * Anything left in it — a comment, `present_anyway`, a flag — is something said, and keeps it.
     */
    private fun tidied(table: JsonObject, preset: String, entry: JsonObject): JsonObject {
        if (entry.size() == 0) table.remove(preset)
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

    /**
     * A tag **unwritten everywhere it is written down** — the tables that carry it, the words that ask for
     * it, and the antonym table that opposes it.
     *
     * The same three places a rename reaches, and for the same reason: a deletion that missed the words
     * would leave them asking for a tag nothing carries, which is legal and silent. What it cannot reach
     * is `art/derivation/` — a rule granting it is a rule to delete on the rules screen, and a deletion
     * here that left one standing would watch the tag come straight back.
     */
    fun deleteTag(tag: String): List<String> {
        if (tag.isEmpty()) return emptyList()
        return buildList {
            addAll(forgetInTables(tag))
            addAll(inEveryWord { word -> forgetInWord(word, tag) })
            addAll(forgetInAntonyms(tag))
        }
    }

    /** Which rules would grant [tag] again — what a deletion cannot reach, and has to say so. */
    fun rulesGranting(tag: String, corpus: Corpus): List<String> =
        corpus.vocabulary.derivation.entries.flatMap { (aspect, derivation) ->
            (derivation.byTag + derivation.byKind).filterValues { it.containsKey(tag) }
                .keys.map { "${aspect.page}: $it" }
        }

    private fun forgetInTables(tag: String): List<String> = pages().mapNotNull { page ->
        val json = read(page)
        var moved = false
        for (preset in json.keySet().toList()) {
            val entry = json.getAsJsonObject(preset) ?: continue
            if (entry.getAsJsonObject(TAGS)?.has(tag) == true) {
                withWeight(json, preset, tag, null)
                moved = true
            }
            if (entry.getAsJsonArray(DROP)?.map { it.asString }?.contains(tag) == true) {
                withDropped(json, preset, tag, dropped = false)
                moved = true
            }
        }
        if (!moved) return@mapNotNull null
        write(page, json)
        "preset_tags/$page"
    }

    private fun forgetInAntonyms(tag: String): List<String> = inEveryAntonymFile { pairs ->
        val left = pairs.filterNot { it.get("first")?.asString == tag || it.get("second")?.asString == tag }
        left.takeIf { it.size != pairs.size }
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

    private fun renameInWords(from: String, to: String): List<String> =
        inEveryWord { word -> renameInWord(word, from, to) }

    /** Whatever [edit] changed, written back — one walk of the corpus for a rename and for a deletion. */
    private fun inEveryWord(edit: (JsonObject) -> Boolean): List<String> =
        WordFile.authoredNames().mapNotNull { name ->
            val file = WordFile.fileFor(name)
            val json = runCatching { JsonParser.parseString(file.readText()).asJsonObject }.getOrNull()
                ?: return@mapNotNull null
            if (!edit(json)) return@mapNotNull null
            file.writeText(GSON.toJson(json) + "\n")
            "word/$name"
        }

    /**
     * **Every place a word file spells a tag** — `restricts` bare, `biases` and `excludes` marked.
     *
     * The mark is what tells a tag from a member where both are legal: `biases` may lean `#frozen` or
     * `minecraft:jungle`, and `excludes` may strike either. `restricts` takes tags alone, so it needs no
     * mark and does not carry one.
     *
     * **This read `query` and `queries` until 2026-09-02**, which no word has had since the world model
     * landed — so a rename moved the tables and the antonyms and left every word asking for the old name,
     * silently, a query for a tag nobody carries being legal and simply finding nothing.
     */
    private fun spellings(word: JsonObject): List<Spelling> = buildList {
        for ((_, byAspect) in word.getAsJsonObject(RESTRICTS)?.entrySet().orEmpty()) {
            add(Spelling.Weighted(byAspect.asJsonObject, marked = false))
        }
        for ((_, byAspect) in word.getAsJsonObject(BIASES)?.entrySet().orEmpty()) {
            add(Spelling.Weighted(byAspect.asJsonObject, marked = true))
        }
        word.getAsJsonObject(EXCLUDES)?.let { struck ->
            for (page in struck.keySet().toList()) add(Spelling.Struck(struck, page))
        }
    }

    /** One place a tag may be written: a weight under its name, or a name in a list of what is struck. */
    private sealed interface Spelling {
        data class Weighted(val holder: JsonObject, val marked: Boolean) : Spelling
        data class Struck(val holder: JsonObject, val page: String) : Spelling
    }

    private fun Spelling.spelt(tag: String) = when (this) {
        is Spelling.Weighted -> if (marked) "${Word.TAG_MARK}$tag" else tag
        is Spelling.Struck -> "${Word.TAG_MARK}$tag"
    }

    /** True where this word file mentioned [from] anywhere it can spell a tag, and now says [to]. */
    fun renameInWord(word: JsonObject, from: String, to: String): Boolean =
        spellings(word).count { where ->
            val was = where.spelt(from)
            val wanted = where.spelt(to)
            when (where) {
                is Spelling.Weighted -> renameKey(where.holder, was, wanted)
                is Spelling.Struck -> restruck(where) { standing ->
                    if (was !in standing) null else standing.map { if (it == was) wanted else it }.distinct()
                }
            }
        } > 0

    /** True where this word file said anything about [tag] and now says nothing. */
    fun forgetInWord(word: JsonObject, tag: String): Boolean =
        spellings(word).count { where ->
            when (where) {
                is Spelling.Weighted -> where.holder.remove(where.spelt(tag)) != null
                is Spelling.Struck -> restruck(where) { standing ->
                    if (where.spelt(tag) !in standing) null else standing - where.spelt(tag)
                }
            }
        } > 0

    /** A list of struck names rewritten, or left alone where [wanted] has nothing to change. */
    private fun restruck(where: Spelling.Struck, wanted: (List<String>) -> List<String>?): Boolean {
        val standing = where.holder.getAsJsonArray(where.page).map { it.asString }
        val left = wanted(standing) ?: return false
        if (left.isEmpty()) where.holder.remove(where.page) else {
            where.holder.add(where.page, JsonArray().apply { left.forEach(::add) })
        }
        return true
    }

    /** True where the key was there and moved. Weights are signed, so the sign travels with it. */
    private fun renameKey(holder: JsonObject?, from: String, to: String): Boolean {
        if (holder?.has(from) != true) return false
        val weight = holder.get(from).asDouble
        holder.remove(from)
        holder.addProperty(to, weight)
        return true
    }

    private fun renameInAntonyms(from: String, to: String): List<String> = inEveryAntonymFile { pairs ->
        var moved = false
        for (body in pairs) {
            for (side in listOf("first", "second")) {
                if (body.get(side)?.asString != from) continue
                body.addProperty(side, to)
                moved = true
            }
        }
        pairs.takeIf { moved }
    }

    /** Every antonym file, rewritten where [wanted] hands back a different set of pairs. */
    private fun inEveryAntonymFile(wanted: (List<JsonObject>) -> List<JsonObject>?): List<String> {
        val directory = WordFile.art.resolve("antonyms")
        return directory.listFiles { file -> file.name.endsWith(JSON_SUFFIX) }.orEmpty().mapNotNull { file ->
            val json = runCatching { JsonParser.parseString(file.readText()).asJsonObject }.getOrNull()
                ?: return@mapNotNull null
            val pairs = json.getAsJsonArray("pairs").orEmpty().map { it.asJsonObject }
            val left = wanted(pairs) ?: return@mapNotNull null
            json.add("pairs", JsonArray().apply { left.forEach(::add) })
            file.writeText(GSON.toJson(json) + "\n")
            "antonyms/${file.name.removeSuffix(JSON_SUFFIX)}"
        }
    }

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray()
}

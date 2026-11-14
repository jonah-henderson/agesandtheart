package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.age.language.WordFormat
import co.voik.agesandtheart.location
import co.voik.agesandtheart.preview.authoring.words.WordListings
import com.mojang.serialization.JsonOps
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * Where the pack's words live on disk, and how one is read and written back.
 *
 * **The codec owns the value and this owns the file**: [WordFormat] ignores `_comment`, so a word is read and
 * written through it with the comment carried beside it, laid first.
 */
object WordFile {

    /** Two-space, unescaped — measured against the corpus, where 121 of 127 files already read like this. */
    private val GSON = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()

    private const val JSON_SUFFIX = ".json"

    /** The pack's resources in the source tree, as [MinecraftRegistries.resourceRoot] finds them. */
    val resources: File by lazy {
        MinecraftRegistries.resourceRoot().toFile().also { root ->
            check(root.resolve("data/${Constants.MOD_ID}/art").isDirectory) {
                "no data/${Constants.MOD_ID}/art under ${root.absolutePath}"
            }
        }
    }

    val art: File get() = resources.resolve("data/${Constants.MOD_ID}/art")

    private val words: File get() = art.resolve("word")

    /** Every word this pack authored, by name. Derived words have no file and are not here. */
    fun authoredNames(): List<String> =
        words.listFiles { file -> file.name.endsWith(JSON_SUFFIX) }
            .orEmpty()
            .map { it.name.removeSuffix(JSON_SUFFIX) }
            .sorted()

    fun fileFor(name: String): File = words.resolve("$name$JSON_SUFFIX")

    fun exists(name: String): Boolean = fileFor(name).isFile

    private val LEGAL_NAME = Regex("[a-z0-9/._-]+")

    /** The word in [name]'s file, or why it will not read. */
    fun read(name: String): Result<Candidate> = runCatching {
        val json = JsonParser.parseString(fileFor(name).readText()).asJsonObject
        val comment = json.get(COMMENT)?.let { said ->
            if (said.isJsonArray) said.asJsonArray.map { it.asString } else listOf(said.asString)
        }.orEmpty()
        val word = WordFormat.mapCodec(name.location()).codec().parse(JsonOps.INSTANCE, json)
            .getOrThrow { problem -> IllegalArgumentException("'$name' does not read: $problem") }
        Candidate.authored(word, comment)
    }

    /** What [candidate]'s file says: the comment first, then what the word is, as the codec spells it. */
    fun textOf(candidate: Candidate): String {
        val encoded = WordFormat.mapCodec(candidate.id).codec().encodeStart(JsonOps.INSTANCE, candidate.asWord())
            .getOrThrow { problem -> IllegalArgumentException("'${candidate.name}' does not write: $problem") }
            .asJsonObject
        val laid = JsonObject()
        // One line stays a string, as the corpus spells it.
        when (candidate.comment.size) {
            0 -> Unit
            1 -> laid.addProperty(COMMENT, candidate.comment.single())
            else -> laid.add(COMMENT, JsonArray().apply { candidate.comment.forEach(::add) })
        }
        for (key in LAID_OUT_IN_ORDER) encoded.get(key)?.let { laid.add(key, it) }
        encoded.keySet().filterNot(LAID_OUT_IN_ORDER::contains).forEach { laid.add(it, encoded.get(it)) }
        return GSON.toJson(laid) + "\n"
    }

    /** [candidate] written to its file, refusing a name no file may have. */
    fun write(candidate: Candidate) {
        require(couldBeAName(candidate.name)) { "'${candidate.name}' cannot be a word's file name" }
        fileFor(candidate.name).writeText(textOf(candidate))
    }

    private const val COMMENT = "_comment"

    /** How a word file reads top to bottom: what it is said as, then what it means. */
    private val LAID_OUT_IN_ORDER = listOf("speech", "takes_as", "meanings")

    /**
     * Whether [name] could be a word's file name — the same rule [write] refuses on, asked in advance.
     *
     * A screen that offers to make a word out of what was typed has to know before it offers, or the
     * offer is a keystroke that leads to a word that cannot be saved.
     */
    fun couldBeAName(name: String): Boolean = name.matches(LEGAL_NAME)

    /** A word renamed: the file, and everything listed under its name ([WordListings.renamed]). */
    fun renameWord(from: String, to: String) {
        if (from == to) return
        // Moved rather than dropped: the caller writes the word out afterwards, and a rename that only deleted would lose it
        // outright if that write then failed.
        fileFor(from).renameTo(fileFor(to))
        WordListings.renamed(from, to)
    }

    /** A word and every trace of it: the file, and everything listed under its name. */
    fun deleteWord(name: String) {
        fileFor(name).delete()
        WordListings.forgotten(name)
    }
}

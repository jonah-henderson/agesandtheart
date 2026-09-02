package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.AgeRecipe
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.resources.Identifier
import java.io.File
import kotlin.random.Random

/**
 * Where an Age's seed comes from, which is a different question from what it is.
 *
 * The same book at two seeds is two Ages, so most of previewing is deciding whether you want the *same*
 * one back or a fresh one - and answering that with a number to remember made it something to manage
 * rather than something to choose.
 */
enum class Seeding(val title: String, val about: String) {
    /** What the game itself gives a book nobody put a seed on - so the preview is what a player would get. */
    SETTLED("the Age's own", "worked out from its name, exactly as the game does for a book with no seed"),

    /** Rolled fresh on the way into the game, which is how you see what else this book can be. */
    DRAWN("a new one each time", "rolled when it opens, so the same book shows you a different Age"),

    /** A number typed, and kept. */
    CHOSEN("one you pick", "a number you type, saved with the book"),
}

/**
 * A book somebody is writing, kept on disk - **the pages, not the Age.**
 *
 * `AgeRecipe` is what the game persists and it holds the *resolved* composition, deliberately: an Age must
 * come back the same however the corpus moves under it (design §4.6). A draft is the other thing. What is
 * being worked on here is the sentence, and the recipe is what it turns into - so keeping the recipe would
 * freeze the answer and lose the question.
 *
 * They live outside the mod's resources because they are nobody's content: `.authoring/` is already where
 * the tool keeps what is its own, and it is git-ignored.
 */
data class AgeDraft(
    val name: String,
    val pages: List<String>,
    val seeding: Seeding = Seeding.SETTLED,
    /** The number [Seeding.CHOSEN] uses, and the last one [Seeding.DRAWN] rolled. */
    val seed: Long = 0L,
) {
    val sentence: String get() = pages.joinToString(" ")

    /** The seed this book would actually be written at. */
    fun seedNow(): Long = when (seeding) {
        Seeding.SETTLED -> settledSeed(name)
        Seeding.DRAWN, Seeding.CHOSEN -> seed
    }

    /** This book with a fresh roll, for [Seeding.DRAWN]. */
    fun rolled(): AgeDraft = copy(seed = Random.nextLong())

    companion object {
        private val GSON = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()

        private const val JSON_SUFFIX = ".json"

        val directory = File(".authoring/ages")

        /**
         * Anything a person types, settled into something that can be a file here and an Age id there.
         *
         * **Minecraft's rule, not ours.** A name becomes `agesandtheart:<path>` and a resource path is
         * lower case with no spaces in it, so `Emerald Deep` cannot be an id — but refusing to accept it
         * made the tool look arbitrary, when all it had to do was write it down as `emerald_deep`.
         * Anything that is not a legal character becomes an underscore, and runs of them collapse.
         */
        fun asAName(said: String): String =
            said.lowercase()
                .map { if (it in LEGAL_CHARACTERS) it else '_' }
                .joinToString("")
                .replace(Regex("_+"), "_")
                .trim('_')

        private const val LEGAL_CHARACTERS = "abcdefghijklmnopqrstuvwxyz0123456789-"

        /**
         * What the game gives an Age of this name when nobody chose a seed.
         *
         * `AgeRecipe.seedFor` rather than a hash of our own, because the point of the settled seed is that
         * the preview is *the Age a player would get* - a different derivation here would preview a world
         * nobody could ever be handed.
         */
        fun settledSeed(name: String): Long =
            AgeRecipe.seedFor(Identifier.fromNamespaceAndPath(Constants.MOD_ID, name.lowercase().ifEmpty { "age" }))

        fun all(): List<AgeDraft> =
            directory.listFiles { file -> file.name.endsWith(JSON_SUFFIX) }.orEmpty()
                .mapNotNull { read(it.name.removeSuffix(JSON_SUFFIX)) }
                .sortedBy { it.name }

        fun read(name: String): AgeDraft? = runCatching {
            val json = JsonParser.parseString(fileFor(name).readText()).asJsonObject
            AgeDraft(
                name = json.get("name")?.asString ?: name,
                pages = json.getAsJsonArray("pages").orEmpty().map { it.asString },
                seeding = json.get("seeding")?.asString
                    ?.let { said -> Seeding.entries.firstOrNull { it.name.equals(said, ignoreCase = true) } }
                    ?: Seeding.SETTLED,
                seed = json.get("seed")?.asLong ?: 0L,
            )
        }.getOrNull()

        fun fileFor(name: String): File = directory.resolve("$name$JSON_SUFFIX")

        fun write(draft: AgeDraft): File {
            require(draft.name.isNotEmpty() && draft.name == asAName(draft.name)) {
                "'${draft.name}' cannot name an Age — try ${asAName(draft.name).ifEmpty { "something with letters in it" }}"
            }
            directory.mkdirs()
            val json = JsonObject().apply {
                addProperty("name", draft.name)
                addProperty("seeding", draft.seeding.name.lowercase())
                // Written even for the settled seeding, because it is what a roll left behind and what a
                // reader wants to see; it simply is not what that seeding uses.
                addProperty("seed", draft.seed)
                add("pages", JsonArray().apply { draft.pages.forEach(::add) })
            }
            return fileFor(draft.name).also { it.writeText(GSON.toJson(json) + "\n") }
        }

        fun delete(name: String) {
            fileFor(name).delete()
        }

        private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray()
    }
}

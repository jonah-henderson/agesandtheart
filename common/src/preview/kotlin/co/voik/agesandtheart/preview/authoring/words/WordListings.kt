package co.voik.agesandtheart.preview.authoring.words

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.age.words.DerivedWords
import co.voik.agesandtheart.age.word.CannotAppearInLoot
import co.voik.agesandtheart.age.word.InkRequirement
import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.age.word.WordRarity
import co.voik.agesandtheart.location
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import net.minecraft.resources.Identifier
import java.io.File

/**
 * What the pack says of a word **away from its file**: how rare it is, the ink it demands, whether it is kept out of loot
 * or never made a word, and what it is called. Each is kept where the game reads it — `art/rarity/`, `art/ink/`, a tag
 * on the entry a derived word is, the language file — so the tool reaches each directory to answer what the word file
 * cannot.
 */
object WordListings {

    /**
     * A word as its listings know it: [key] in the rarity lists, [name] in the ink lists, [id], and for a derived word the
     * tag directory of the registry it is an entry of, where its ink and its loot fence are tags.
     */
    data class Listed(val key: String, val name: String, val id: Identifier, val tagDirectory: String? = null) {
        companion object {
            /** A word this pack wrote, listed by its name. */
            fun authored(name: String): Listed = Listed(name, name, name.location())
        }
    }

    /** Which loot bucket and which ink a word is listed in. A word in no bucket falls to the default. */
    data class Listing(val rarity: String?, val ink: String?)

    /** How likely a bucket is to be drawn — what a bucket means — and how many words are listed in it. */
    data class Standing(val weight: Double, val listed: Int)

    /** What the rarity column shows for a word in `#agesandtheart:cannot_appear_in_loot`. */
    const val NO_LOOT = "no loot"

    /** What the word lists show for a thing in `#agesandtheart:does_not_have_a_word`. */
    const val NO_WORD = "no word"

    private val GSON = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
    private const val JSON_SUFFIX = ".json"
    private const val TAGS = "tags"

    private val namespace: File by lazy {
        MinecraftRegistries.resourceRoot().toFile().resolve("data/${Constants.MOD_ID}").also { root ->
            check(root.resolve("art").isDirectory) { "no art under ${root.absolutePath}" }
        }
    }

    private val rarityLists: File get() = namespace.resolve(WordRarity.RARITY_DIRECTORY)

    private val inkLists: File get() = namespace.resolve(InkRequirement.INK_DIRECTORY)

    private val keptOutOfLootList: File get() = namespace.resolve(WordRarity.KEPT_OUT_OF_LOOT_FILE)

    /**
     * The language file, where the game already keeps what a word is **called**, as against what it is named:
     * `WordNames.readable` reads `word.<namespace>.<path>` and falls back to the id opened out. So the display name is no
     * field of the word: a second copy in the datapack could not be translated.
     */
    private val language: File by lazy { MinecraftRegistries.resourceRoot().toFile().resolve("assets/${Constants.MOD_ID}/lang/en_us.json") }

    // -- what a word is called ------------------------------------------------------------------------

    /** What this word is called, or null where nothing says and the fallback is doing the work. */
    fun displayOf(id: String): String? = runCatching {
        JsonParser.parseString(language.readText()).asJsonObject.get(languageKeyFor(id))?.asString
    }.getOrNull()

    /** What the fallback would show — the id, opened out and title-cased, as `WordNames` does it. */
    fun fallbackDisplay(id: String): String =
        id.substringAfter(':').replace('_', ' ').split(' ').joinToString(" ") { part -> part.replaceFirstChar(Char::titlecase) }

    /**
     * Names this word [called], or takes the entry out so the fallback shows again — writing the file only where that
     * changes it, every other entry left where it stands, so a rename is one line of diff.
     */
    fun setDisplay(id: String, called: String?) {
        val json = JsonParser.parseString(language.readText()).asJsonObject
        val key = languageKeyFor(id)
        if (json.get(key)?.asString == called) return
        if (called == null) json.remove(key) else json.addProperty(key, called)
        language.writeText(GSON.toJson(json) + "\n")
    }

    private fun languageKeyFor(id: String): String {
        val namespace = id.substringBefore(':', Constants.MOD_ID)
        val path = id.substringAfter(':')
        return "word.$namespace.$path"
    }

    // -- a word's listings moved or forgotten -----------------------------------------------------------

    /** Everything listed under the authored word [from] moved to [to]: its rarity, its ink, its name and its loot fence. */
    fun renamed(from: String, to: String) {
        if (from == to) return
        val listing = listingFor(from)
        val called = displayOf(from)
        val wasKeptOut = from in authoredKeptOutOfLoot()
        forgotten(from)
        listing.rarity?.let { list(rarityLists, to, it) }
        listing.ink?.let { list(inkLists, to, it) }
        called?.let { setDisplay(to, it) }
        if (wasKeptOut) keepAuthoredOutOfLoot(to, true)
    }

    /** Every trace of the authored word [name] in the listings: its rarity, its ink, its name and its loot fence. */
    fun forgotten(name: String) {
        keepAuthoredOutOfLoot(name, false)
        list(rarityLists, name, null)
        list(inkLists, name, null)
        setDisplay(name, null)
    }

    // -- every word at once ------------------------------------------------------------------------------

    /** Every word's rarity and ink in one pass — the lists read once, not once per row. */
    fun everyListing(): Map<String, Listing> {
        val rarity = namesByBucket(rarityLists)
        val ink = namesByBucket(inkLists)
        return (rarity.keys + ink.keys).associateWith { Listing(rarity[it], ink[it]) }
    }

    /** Every id an ink tag holds, by tag directory and then by id, found by walking `tags/` so no directory is missed. */
    fun everyInkTag(): Map<String, Map<String, String>> {
        val byDirectory = mutableMapOf<String, MutableMap<String, String>>()
        // Cheapest first, so an id both tags carry ends on the dearest, which is what `InkRequirement` reads.
        for ((tier, tag) in InkRequirement.TAG_NAMES.entries.reversed()) {
            val fileName = "${tag.path}$JSON_SUFFIX"
            for (file in namespace.resolve(TAGS).walkTopDown().filter { it.isFile && it.name == fileName }) {
                val directory = file.parentFile.relativeTo(namespace).invariantSeparatorsPath
                val tiers = byDirectory.getOrPut(directory, ::mutableMapOf)
                tagValuesIn(file).forEach { tiers[it] = tier.key }
            }
        }
        return byDirectory
    }

    /** Every id [tag] holds, by tag directory. */
    fun everyIdTagged(tag: Identifier): Map<String, Set<String>> {
        val fileName = "${tag.path}$JSON_SUFFIX"
        return namespace.resolve(TAGS).walkTopDown()
            .filter { it.isFile && it.name == fileName }
            .associate { file -> file.parentFile.relativeTo(namespace).invariantSeparatorsPath to tagValuesIn(file).toSet() }
    }

    /** The authored words listed in [WordRarity.KEPT_OUT_OF_LOOT_FILE]. */
    fun authoredKeptOutOfLoot(): Set<String> {
        val file = keptOutOfLootList
        if (!file.isFile) return emptySet()
        val words = JsonParser.parseString(file.readText()).asJsonObject.getAsJsonArray("words")
        return words?.map { it.asString }.orEmpty().toSet()
    }

    // -- the buckets -------------------------------------------------------------------------------------

    /** The rarity buckets a word can be listed in, commonest first by weight, the default left out. */
    fun rarityBuckets(): List<String> {
        val standings = rarityStandings()
        return standings.keys.filter { it != defaultBucket() }.sortedByDescending { standings.getValue(it).weight }
    }

    /** The bucket every unlisted word is in, which the tool shows for a word with no rarity set. */
    fun defaultBucket(): String? = bucketNames(rarityLists).firstOrNull { name -> bucketJson(name)?.get("default")?.asBoolean == true }

    fun rarityStandings(): Map<String, Standing> = bucketNames(rarityLists).associateWith { name ->
        val json = bucketJson(name)
        Standing(json?.get("weight")?.asDouble ?: 0.0, json?.getAsJsonArray("words")?.size() ?: 0)
    }

    /** The rarities a word may be listed in, commonest first, and then [NO_LOOT]. */
    fun rarityChoices(): List<String> = rarityBuckets() + NO_LOOT

    /** The ink qualities, cheapest first — [InkTier]'s own order, which is what "better" means. */
    fun inkTiers(): List<String> {
        val known = InkTier.entries.map { it.key }
        return bucketNames(inkLists).sortedBy { known.indexOf(it).takeIf { at -> at >= 0 } ?: known.size }
    }

    // -- one word ----------------------------------------------------------------------------------------

    fun listingFor(key: String) = Listing(rarity = bucketNaming(rarityLists, key), ink = bucketNaming(inkLists, key))

    /** [listingFor], with a word kept out of loot reading as [NO_LOOT] whatever bucket it is also in. */
    fun listingOf(listed: Listed): Listing {
        val listing = listingFor(listed.key)
        return if (isKeptOutOfLoot(listed)) listing.copy(rarity = NO_LOOT) else listing
    }

    /**
     * Sets [listed]'s rarity to a bucket, to [NO_LOOT], or to neither — neither and the default bucket being the same.
     * Kept out of loot by a tag on the entry a derived word is, and by name where it is none, as ink is.
     */
    fun setRarity(listed: Listed, choice: String?) {
        val keptOut = choice == NO_LOOT
        val directory = listed.tagDirectory
        if (directory == null) keepAuthoredOutOfLoot(listed.name, keptOut) else putInTag(tagFile(directory, CannotAppearInLoot.TAG_NAME), listed.id.toString(), keptOut)
        list(rarityLists, listed.key, choice.takeUnless { keptOut || it == defaultBucket() })
    }

    /** Whether the thing [listed] names is tagged never to become a word; offline the tag binds nothing, so it still shows. */
    fun hasNoWord(listed: Listed): Boolean {
        val directory = listed.tagDirectory ?: return false
        return listed.id.toString() in tagValuesIn(tagFile(directory, DerivedWords.DOES_NOT_HAVE_A_WORD))
    }

    /** Tags or untags the thing [listed] names; a word naming no registry entry has nothing to tag. */
    fun setHasNoWord(listed: Listed, hasNoWord: Boolean) {
        val directory = listed.tagDirectory ?: return
        putInTag(tagFile(directory, DerivedWords.DOES_NOT_HAVE_A_WORD), listed.id.toString(), hasNoWord)
    }

    /** Which ink [listed] demands, as `InkRequirement.tierFor` asks it: a tag on its entry first, then its name. */
    fun inkOf(listed: Listed): String? {
        val tagged = listed.tagDirectory?.let { inkTagOn(listed.id.toString(), it) }
        return tagged ?: bucketNaming(inkLists, listed.name)
    }

    /** Sets the ink [listed] demands — a tag on the entry a derived word is, and by name for an authored one. */
    fun setInk(listed: Listed, tier: String?) {
        val directory = listed.tagDirectory
        if (directory == null) list(inkLists, listed.name, tier) else inkTagFor(listed.id.toString(), directory, tier)
    }

    // -- the files ---------------------------------------------------------------------------------------

    private fun isKeptOutOfLoot(listed: Listed): Boolean = when (val directory = listed.tagDirectory) {
        null -> listed.name in authoredKeptOutOfLoot()
        else -> listed.id.toString() in tagValuesIn(tagFile(directory, CannotAppearInLoot.TAG_NAME))
    }

    private fun keepAuthoredOutOfLoot(name: String, keptOut: Boolean) {
        val file = keptOutOfLootList
        if (!file.isFile && !keptOut) return
        val json = if (file.isFile) JsonParser.parseString(file.readText()).asJsonObject else JsonObject()
        val listed = authoredKeptOutOfLoot()
        if (keptOut == (name in listed)) return
        val kept = if (keptOut) (listed + name).sorted() else (listed - name).sorted()
        json.add("words", JsonArray().apply { kept.forEach(::add) })
        file.writeText(GSON.toJson(json) + "\n")
    }

    private fun bucketJson(name: String): JsonObject? = runCatching {
        JsonParser.parseString(rarityLists.resolve("$name$JSON_SUFFIX").readText()).asJsonObject
    }.getOrNull()

    private fun namesByBucket(directory: File): Map<String, String> = buildMap {
        for (file in directory.listFiles { it.name.endsWith(JSON_SUFFIX) }.orEmpty()) {
            val bucket = file.name.removeSuffix(JSON_SUFFIX)
            runCatching { JsonParser.parseString(file.readText()).asJsonObject.getAsJsonArray("words")?.forEach { put(it.asString, bucket) } }
        }
    }

    /** Lists [name] under [bucket] in [root] and takes it out of every sibling, so a word is in one bucket or none. */
    private fun list(root: File, name: String, bucket: String?) {
        for (file in root.listFiles { it.name.endsWith(JSON_SUFFIX) }.orEmpty()) {
            val json = JsonParser.parseString(file.readText()).asJsonObject
            val listed = json.getAsJsonArray("words")?.map { it.asString }.orEmpty()
            val wanted = file.name.removeSuffix(JSON_SUFFIX) == bucket
            if (wanted == (name in listed)) continue
            val kept = if (wanted) (listed + name).sorted() else listed - name
            if (kept.isEmpty()) json.remove("words") else json.add("words", JsonArray().apply { kept.forEach(::add) })
            file.writeText(GSON.toJson(json) + "\n")
        }
    }

    /** Puts [id] in the ink tag for [tier] under [directory] and takes it out of the others. */
    private fun inkTagFor(id: String, directory: String, tier: String?) {
        for ((each, tag) in InkRequirement.TAG_NAMES) putInTag(tagFile(directory, tag), id, wanted = each.key == tier)
    }

    /** Puts [id] in the tag [file] or takes it out, writing the file only where that changes it. */
    private fun putInTag(file: File, id: String, wanted: Boolean) {
        if (!file.isFile && !wanted) return
        val json = if (file.isFile) JsonParser.parseString(file.readText()).asJsonObject else JsonObject()
        val entries = json.getAsJsonArray("values")?.toList().orEmpty()
        if (wanted == entries.any { idOf(it) == id }) return
        val kept = if (wanted) (entries + JsonPrimitive(id)).sortedBy(::idOf) else entries.filterNot { idOf(it) == id }
        json.add("values", JsonArray().apply { kept.forEach(::add) })
        if (!json.has("replace")) json.addProperty("replace", false)
        file.parentFile.mkdirs()
        file.writeText(GSON.toJson(json) + "\n")
    }

    /** The dearest ink tag under [directory] holding [id], or null where none does. */
    private fun inkTagOn(id: String, directory: String): String? =
        InkRequirement.TAG_NAMES.entries.firstOrNull { (_, tag) -> id in tagValuesIn(tagFile(directory, tag)) }?.let { (tier, _) -> tier.key }

    private fun tagFile(directory: String, tag: Identifier): File = namespace.resolve("$directory/${tag.path}$JSON_SUFFIX")

    private fun tagValuesIn(file: File): List<String> {
        if (!file.isFile) return emptyList()
        val values = JsonParser.parseString(file.readText()).asJsonObject.getAsJsonArray("values")
        return values?.map(::idOf).orEmpty()
    }

    /** The id a tag entry names: a bare id, or an object's `id` where the entry is optional — another mod's ore. */
    private fun idOf(entry: JsonElement): String = if (entry.isJsonObject) entry.asJsonObject.get("id").asString else entry.asString

    private fun bucketNames(directory: File) =
        directory.listFiles { file -> file.name.endsWith(JSON_SUFFIX) }.orEmpty().map { it.name.removeSuffix(JSON_SUFFIX) }.sorted()

    private fun bucketNaming(directory: File, name: String): String? =
        directory.listFiles { file -> file.name.endsWith(JSON_SUFFIX) }.orEmpty()
            .firstOrNull { file ->
                val json = runCatching { JsonParser.parseString(file.readText()).asJsonObject }.getOrNull()
                json?.getAsJsonArray("words")?.any { it.asString == name } == true
            }
            ?.name?.removeSuffix(JSON_SUFFIX)
}

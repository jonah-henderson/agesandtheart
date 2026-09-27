package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.age.word.CannotAppearInLoot
import co.voik.agesandtheart.age.word.DerivedWords
import co.voik.agesandtheart.age.word.InkRequirement
import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.age.word.WordRarity
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.resources.Identifier
import java.io.File

/**
 * Where the pack's words live on disk, and how one is read and written back.
 *
 * **The tool owns the file text, not just the value.** `Word.mapCodec` ignores `_comment` and widens
 * `aspects`, so a word round-tripped through the codec would come back with its reasoning gone and its
 * derived reach frozen into the file. Everything here works on the JSON.
 */
object WordFile {

    /** Two-space, unescaped — measured against the corpus, where 121 of 127 files already read like this. */
    private val GSON = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()

    private const val JSON_SUFFIX = ".json"

    private const val TAGS = "tags"

    /** What the rarity column shows for a word in `#agesandtheart:cannot_appear_in_loot`. */
    const val NO_LOOT = "no loot"

    /** What the word lists show for a thing in `#agesandtheart:does_not_have_a_word`. */
    const val NO_WORD = "no word"

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

    /** The word [name] as its file says it, or what stopped it being read. */
    fun read(name: String): Result<Candidate> = runCatching {
        val file = fileFor(name)
        require(file.isFile) { "there is no ${file.path}" }
        JsonParser.parseString(file.readText()).asJsonObject
    }.mapCatching { json -> Candidate.read(name, json).getOrThrow() }

    /**
     * [candidate] written to its file, and the text that was written.
     *
     * **A word's name must survive `ResourcePathCheck`**, which insists every shipped resource path is
     * lowercase and punctuation-free — one capitalised filename once cost the whole script font, and a
     * tool that can create files is exactly where that gets in.
     */
    fun write(candidate: Candidate): File {
        require(candidate.name.matches(LEGAL_NAME)) {
            "'${candidate.name}' cannot be a resource path — lower case, digits, and _ - . / only"
        }
        val file = fileFor(candidate.name)
        file.parentFile.mkdirs()
        file.writeText(textOf(candidate))
        return file
    }

    /** What [write] would put in the file, so a preview and the write cannot say different things. */
    fun textOf(candidate: Candidate): String = GSON.toJson(candidate.asJson()) + "\n"

    private val LEGAL_NAME = Regex("[a-z0-9/._-]+")

    /**
     * Whether [name] could be a word's file name — the same rule [write] refuses on, asked in advance.
     *
     * A screen that offers to make a word out of what was typed has to know before it offers, or the
     * offer is a keystroke that leads to a word that cannot be saved.
     */
    fun couldBeAName(name: String): Boolean = name.matches(LEGAL_NAME)

    /**
     * Which loot bucket and which ink a word is listed in — **stored away from the word**, in
     * `art/rarity/` and `art/ink/`, which is why the tool has to reach two more directories to answer a
     * question the word file cannot.
     *
     * 88 of 127 words are in no bucket at all (`vocabulary-pass-plan.md` §8), so how hard two thirds of
     * the corpus is to find is unauthored. Asking while a word is being written is where that stops.
     */
    data class Listing(val rarity: String?, val ink: String?)

    /**
     * What a word is **called**, as against what it is **named** — the language file, where the game
     * already keeps it.
     *
     * `WordNames.readable` reads `word.<namespace>.<path>` and falls back to the id with its underscores
     * opened out, so `rising_east` shows as "Rising East" until somebody says otherwise. Everything a
     * player sees goes through it already: the page, the desk, the toast, the book's title, the readout.
     *
     * **So the display name is not a field on the word**, and should not become one: a second copy in the
     * datapack could not be translated, and would have to win or lose against this one.
     */
    private val language: File get() = resources.resolve("assets/${Constants.MOD_ID}/lang/en_us.json")

    private fun keyFor(id: String): String {
        val namespace = id.substringBefore(':', Constants.MOD_ID)
        val path = id.substringAfter(':')
        return "word.$namespace.$path"
    }

    /** What this word is called, or null where nothing says and the fallback is doing the work. */
    fun displayOf(id: String): String? = runCatching {
        JsonParser.parseString(language.readText()).asJsonObject.get(keyFor(id))?.asString
    }.getOrNull()

    /** What the fallback would show — the id, opened out and title-cased, as `WordNames` does it. */
    fun fallbackDisplay(id: String): String =
        id.substringAfter(':').replace('_', ' ').split(' ')
            .joinToString(" ") { part -> part.replaceFirstChar(Char::titlecase) }

    /** Names this word [called], or takes the entry out so the fallback shows again. */
    fun setDisplay(id: String, called: String?) {
        val json = JsonParser.parseString(language.readText()).asJsonObject
        val key = keyFor(id)
        if (called == null) json.remove(key) else json.addProperty(key, called)
        // Sorted, as the file already is, so a rename is one line of diff rather than a reshuffle.
        val sorted = JsonObject()
        json.keySet().sorted().forEach { sorted.add(it, json.get(it)) }
        language.writeText(GSON.toJson(sorted) + "\n")
    }

    /**
     * A word renamed — **the file, and everything that refers to it by name.**
     *
     * Writing the new file was all this used to do, which left the old one behind as a second word and
     * stranded the rarity, the ink and the display name under a name nothing would look up again.
     */
    fun renameWord(from: String, to: String) {
        if (from == to) return
        val listing = listingFor(from)
        val called = displayOf(from)
        val wasKeptOut = from in authoredKeptOutOfLoot()
        // Moved rather than dropped: the caller writes the word out afterwards, but a rename that only
        // deleted would lose it outright if that write then failed.
        fileFor(from).renameTo(fileFor(to))
        list(rarityLists, from, null)
        list(inkLists, from, null)
        setDisplay(from, null)
        listing.rarity?.let { list(rarityLists, to, it) }
        listing.ink?.let { list(inkLists, to, it) }
        called?.let { setDisplay(to, it) }
        if (wasKeptOut) {
            keepAuthoredOutOfLoot(from, false)
            keepAuthoredOutOfLoot(to, true)
        }
    }

    /** A word and every trace of it — the file, its rarity, its ink and what it was shown as. */
    fun deleteWord(name: String) {
        fileFor(name).delete()
        keepAuthoredOutOfLoot(name, false)
        list(rarityLists, name, null)
        list(inkLists, name, null)
        setDisplay(name, null)
    }

    private val rarityLists: File get() = art.parentFile.resolve(WordRarity.RARITY_DIRECTORY)

    private val inkLists: File get() = art.parentFile.resolve(InkRequirement.INK_DIRECTORY)

    /**
     * Every word's rarity and ink in one pass — **five files read once, not once per row.**
     *
     * [listingFor] opens every rarity and ink file to answer for one word, which is fine for one and is
     * eight thousand reads for a table of sixteen hundred. The lists are small and the inversion is cheap.
     */
    fun everyListing(): Map<String, Listing> {
        val rarity = namesByBucket(rarityLists)
        val ink = namesByBucket(inkLists)
        return (rarity.keys + ink.keys).associateWith { Listing(rarity[it], ink[it]) }
    }

    /**
     * Every id an ink tag holds, by tag directory and then by id — the same saving for the derived half.
     *
     * Found by walking `tags/` rather than from a list of registries, so a directory is never missed.
     */
    fun everyInkTag(): Map<String, Map<String, String>> {
        val namespace = art.parentFile
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

    private fun namesByBucket(directory: File): Map<String, String> = buildMap {
        for (file in directory.listFiles { it.name.endsWith(JSON_SUFFIX) }.orEmpty()) {
            val bucket = file.name.removeSuffix(JSON_SUFFIX)
            runCatching {
                JsonParser.parseString(file.readText()).asJsonObject
                    .getAsJsonArray("words")?.forEach { put(it.asString, bucket) }
            }
        }
    }

    fun listingFor(name: String) = Listing(
        rarity = bucketNaming(rarityLists, name),
        ink = bucketNaming(inkLists, name),
    )

    /**
     * The rarity buckets a word can be **listed** in, commonest first — by weight, not by file name. The
     * default bucket is left out: listing a word there says nothing an unlisted word does not.
     */
    fun rarityBuckets(): List<String> {
        val standings = rarityStandings()
        return standings.keys.filter { it != defaultBucket() }.sortedByDescending { standings.getValue(it).weight }
    }

    /** The bucket every unlisted word is in, which the tool shows for a word with no rarity set. */
    fun defaultBucket(): String? = bucketNames(rarityLists).firstOrNull { name ->
        bucketJson(name)?.get("default")?.asBoolean == true
    }

    /**
     * How likely each bucket is to be drawn, and how many words are listed in it.
     *
     * **The weight is what a bucket means**: a found page rolls a bucket by weight before it picks a word,
     * so a bucket's share of pages is its weight over the sum, whatever it lists.
     */
    fun rarityStandings(): Map<String, Standing> =
        bucketNames(rarityLists).associateWith { name ->
            val json = bucketJson(name)
            Standing(json?.get("weight")?.asDouble ?: 0.0, json?.getAsJsonArray("words")?.size() ?: 0)
        }

    data class Standing(val weight: Double, val listed: Int)

    private fun bucketJson(name: String): JsonObject? = runCatching {
        JsonParser.parseString(rarityLists.resolve("$name$JSON_SUFFIX").readText()).asJsonObject
    }.getOrNull()

    /** The ink qualities, cheapest first — [InkTier]'s own order, which is what "better" means. */
    fun inkTiers(): List<String> {
        val known = InkTier.entries.map { it.key }
        return bucketNames(inkLists).sortedBy { known.indexOf(it).takeIf { at -> at >= 0 } ?: known.size }
    }

    /** Lists [key] — a [Candidate.listingKey] — under the rarity [bucket], or in none. */
    fun setRarity(key: String, bucket: String?) = list(rarityLists, key, bucket)

    /** The rarities a word may be listed in, commonest first, and then [NO_LOOT]. */
    fun rarityChoices(): List<String> = rarityBuckets() + NO_LOOT

    /** [listingFor], with a word kept out of loot reading as [NO_LOOT] whatever bucket it is also in. */
    fun listingOf(candidate: Candidate): Listing {
        val listing = listingFor(candidate.listingKey)
        return if (isKeptOutOfLoot(candidate)) listing.copy(rarity = NO_LOOT) else listing
    }

    /**
     * Sets [candidate]'s rarity to a bucket, to [NO_LOOT], or to neither — neither, and the default
     * bucket, being the same thing. The two are exclusive: a word kept out of loot is taken out of its
     * bucket, so leaving [NO_LOOT] finds it in none.
     *
     * Kept out by a tag on the thing a word names, and by name where it names nothing, as ink is.
     */
    fun setRarity(candidate: Candidate, choice: String?) {
        val keptOut = choice == NO_LOOT
        val directory = candidate.tagDirectory
        if (directory == null) {
            keepAuthoredOutOfLoot(candidate.name, keptOut)
        } else {
            putInTag(tagFile(directory, CannotAppearInLoot.TAG_NAME), candidate.id.toString(), keptOut)
        }
        val bucket = choice.takeUnless { keptOut || it == defaultBucket() }
        setRarity(candidate.listingKey, bucket)
    }

    private fun isKeptOutOfLoot(candidate: Candidate): Boolean = when (candidate.tagDirectory) {
        null -> candidate.name in authoredKeptOutOfLoot()
        else -> carries(candidate, CannotAppearInLoot.TAG_NAME)
    }

    private val keptOutOfLootList: File
        get() = art.parentFile.resolve(WordRarity.KEPT_OUT_OF_LOOT_FILE)

    /** The authored words listed in [WordRarity.KEPT_OUT_OF_LOOT_FILE]. */
    fun authoredKeptOutOfLoot(): Set<String> {
        val file = keptOutOfLootList
        if (!file.isFile) return emptySet()
        val words = JsonParser.parseString(file.readText()).asJsonObject.getAsJsonArray("words")
        return words?.map { it.asString }.orEmpty().toSet()
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

    /**
     * Whether the thing [candidate] names is tagged to never become a word. The offline corpus binds no
     * tags, so the word is still here to be seen and untagged.
     */
    fun hasNoWord(candidate: Candidate) = carries(candidate, DerivedWords.DOES_NOT_HAVE_A_WORD)

    /** Tags or untags the thing [candidate] names; a word naming no registry entry has nothing to tag. */
    fun setHasNoWord(candidate: Candidate, hasNoWord: Boolean) {
        val directory = candidate.tagDirectory ?: return
        putInTag(tagFile(directory, DerivedWords.DOES_NOT_HAVE_A_WORD), candidate.id.toString(), hasNoWord)
    }

    private fun carries(candidate: Candidate, tag: Identifier): Boolean {
        val directory = candidate.tagDirectory ?: return false
        return candidate.id.toString() in tagValuesIn(tagFile(directory, tag))
    }

    /** Every id [tag] holds, by tag directory — [everyInkTag]'s saving for a whole table. */
    fun everyIdTagged(tag: Identifier): Map<String, Set<String>> {
        val namespace = art.parentFile
        val fileName = "${tag.path}$JSON_SUFFIX"
        return namespace.resolve(TAGS).walkTopDown()
            .filter { it.isFile && it.name == fileName }
            .associate { file ->
                file.parentFile.relativeTo(namespace).invariantSeparatorsPath to tagValuesIn(file).toSet()
            }
    }

    /**
     * Which ink [candidate] demands, asked as `InkRequirement.tierFor` asks it: a tag on the entry it names
     * first, then its name in `art/ink/`.
     */
    fun inkOf(candidate: Candidate): String? {
        val tagged = candidate.tagDirectory?.let { inkTagOn(candidate.id.toString(), it) }
        return tagged ?: bucketNaming(inkLists, candidate.name)
    }

    /**
     * Sets the ink [candidate] demands — **as a tag on the entry where it names one, and by name where it
     * does not.**
     *
     * The two halves of the vocabulary answer through different channels and this is the reason: a derived
     * word *is* a registry entry, so tagging the entry is what lets another mod's ore be worth the good ink
     * without anybody editing our files. An authored word names nothing and has to be listed.
     */
    fun setInk(candidate: Candidate, tier: String?) {
        val directory = candidate.tagDirectory
        if (directory == null) {
            list(inkLists, candidate.name, tier)
        } else {
            inkTagFor(candidate.id.toString(), directory, tier)
        }
    }

    /**
     * Lists [name] under [bucket] in [root] and takes it out of every sibling, so a word is in one bucket or
     * none rather than quietly in two.
     */
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
        val values = tagValuesIn(file)
        if (wanted == (id in values)) return
        val kept = if (wanted) (values + id).sorted() else values - id
        json.add("values", JsonArray().apply { kept.forEach(::add) })
        if (!json.has("replace")) json.addProperty("replace", false)
        file.parentFile.mkdirs()
        file.writeText(GSON.toJson(json) + "\n")
    }

    /** The dearest ink tag under [directory] holding [id], or null where none does. */
    private fun inkTagOn(id: String, directory: String): String? =
        InkRequirement.TAG_NAMES.entries.firstOrNull { (_, tag) -> id in tagValuesIn(tagFile(directory, tag)) }
            ?.let { (tier, _) -> tier.key }

    private fun tagFile(directory: String, tag: Identifier): File =
        art.parentFile.resolve("$directory/${tag.path}$JSON_SUFFIX")

    private fun tagValuesIn(file: File): List<String> {
        if (!file.isFile) return emptyList()
        val values = JsonParser.parseString(file.readText()).asJsonObject.getAsJsonArray("values")
        return values?.map { it.asString }.orEmpty()
    }

    private fun bucketNames(directory: File) =
        directory.listFiles { file -> file.name.endsWith(JSON_SUFFIX) }.orEmpty()
            .map { it.name.removeSuffix(JSON_SUFFIX) }
            .sorted()

    private fun bucketNaming(directory: File, name: String): String? =
        directory.listFiles { file -> file.name.endsWith(JSON_SUFFIX) }.orEmpty()
            .firstOrNull { file ->
                val json = runCatching { JsonParser.parseString(file.readText()).asJsonObject }.getOrNull()
                json?.getAsJsonArray("words")?.any { it.asString == name } == true
            }
            ?.name?.removeSuffix(JSON_SUFFIX)
}

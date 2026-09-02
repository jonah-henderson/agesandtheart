package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.age.word.InkTier
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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

    private const val NAMESPACE = "agesandtheart"

    private const val JSON_SUFFIX = ".json"

    /**
     * The pack's data directory, found from wherever the tool was started.
     *
     * Two candidates for the same reason `MinecraftRegistries.shippedData()` has two: the repository root
     * is what a person types, and the module directory is what Gradle would hand a task.
     */
    val resources: File by lazy {
        listOf(File("common/src/main/resources"), File("src/main/resources"))
            .firstOrNull { it.resolve("data/$NAMESPACE/art").isDirectory }
            ?: error("Cannot find the mod's resources from ${File("").absolutePath}")
    }

    val art: File get() = resources.resolve("data/$NAMESPACE/art")

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
    private val language: File get() = resources.resolve("assets/$NAMESPACE/lang/en_us.json")

    private fun keyFor(id: String): String {
        val namespace = id.substringBefore(':', NAMESPACE)
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
        // Moved rather than dropped: the caller writes the word out afterwards, but a rename that only
        // deleted would lose it outright if that write then failed.
        fileFor(from).renameTo(fileFor(to))
        list("rarity", from, null)
        list("ink", from, null)
        setDisplay(from, null)
        listing.rarity?.let { list("rarity", to, it) }
        listing.ink?.let { list("ink", to, it) }
        called?.let { setDisplay(to, it) }
    }

    /** A word and every trace of it — the file, its rarity, its ink and what it was shown as. */
    fun deleteWord(name: String) {
        fileFor(name).delete()
        list("rarity", name, null)
        list("ink", name, null)
        setDisplay(name, null)
    }

    /**
     * Every word's rarity and ink in one pass — **five files read once, not once per row.**
     *
     * [listingFor] opens every rarity and ink file to answer for one word, which is fine for one and is
     * eight thousand reads for a table of sixteen hundred. The lists are small and the inversion is cheap.
     */
    fun everyListing(): Map<String, Listing> {
        val rarity = namesByBucket(art.resolve("rarity"))
        val ink = namesByBucket(art.resolve("ink"))
        return (rarity.keys + ink.keys).associateWith { Listing(rarity[it], ink[it]) }
    }

    /** Every id an ink tag holds, by id — the same saving for the derived half. */
    fun everyInkTag(where: String): Map<String, String> = buildMap {
        for (tier in listOf("fine", "masterwork")) {
            val file = art.parentFile.resolve("tags/$where/requires_${tier}_ink.json")
            if (!file.isFile) continue
            JsonParser.parseString(file.readText()).asJsonObject
                .getAsJsonArray("values")?.forEach { put(it.asString, tier) }
        }
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
        rarity = bucketNaming(art.resolve("rarity"), name),
        ink = bucketNaming(art.resolve("ink"), name),
    )

    /**
     * The rarity buckets, **commonest first** — by the weight each one carries, not by file name.
     *
     * Alphabetical put them in common, rare, uncommon order, which is nobody's idea of a scale and made
     * stepping through them in the tool read as random.
     */
    fun rarityBuckets(): List<String> {
        val directory = art.resolve("rarity")
        return bucketNames(directory).sortedByDescending { name ->
            runCatching {
                JsonParser.parseString(directory.resolve("$name$JSON_SUFFIX").readText())
                    .asJsonObject.get("weight").asDouble
            }.getOrDefault(0.0)
        }
    }

    /**
     * How likely each bucket is to be drawn, and how many words are already in it.
     *
     * **The weight is what a bucket means**, and the pick used to offer three names with nothing to
     * choose between them. `common` at 60 against `rare` at 10 is the whole difference, and it is one
     * number sitting in the file the picker is about to write to.
     */
    fun rarityStandings(): Map<String, Pair<Double, Int>> {
        val directory = art.resolve("rarity")
        return bucketNames(directory).associateWith { name ->
            val json = runCatching {
                JsonParser.parseString(directory.resolve("$name$JSON_SUFFIX").readText()).asJsonObject
            }.getOrNull()
            val weight = json?.get("weight")?.asDouble ?: 0.0
            weight to (json?.getAsJsonArray("words")?.size() ?: 0)
        }
    }

    /** The ink qualities, cheapest first — [InkTier]'s own order, which is what "better" means. */
    fun inkTiers(): List<String> {
        val known = InkTier.entries.map { it.key }
        return bucketNames(art.resolve("ink")).sortedBy { known.indexOf(it).takeIf { at -> at >= 0 } ?: known.size }
    }

    /**
     * Lists [name] under [bucket] in [directory] and takes it out of every sibling, so a word is in one
     * bucket or none rather than quietly in two.
     */
    fun list(directory: String, name: String, bucket: String?) {
        val root = art.resolve(directory)
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

    /**
     * Which ink a **derived** word demands — written as a tag on the thing it names, not as a name in
     * `art/ink/`.
     *
     * The two halves of the vocabulary answer through different channels and this is the reason: a derived
     * word *is* a registry entry, so tagging the entry is what lets another mod's ore be worth the good ink
     * without anybody editing our files. An authored word names nothing and has to be listed.
     *
     * Which registry it is decides which tag file, and the id says: a block, a biome or a structure set.
     */
    fun inkTagFor(id: String, where: String, tier: String?) {
        for (each in listOf("fine", "masterwork")) {
            val file = art.parentFile.resolve("tags/$where/requires_${each}_ink.json")
            if (!file.isFile && each != tier) continue
            val json = if (file.isFile) JsonParser.parseString(file.readText()).asJsonObject else JsonObject()
            val values = json.getAsJsonArray("values")?.map { it.asString }.orEmpty()
            val wanted = each == tier
            if (wanted == (id in values)) continue
            val kept = if (wanted) (values + id).sorted() else values - id
            json.add("values", JsonArray().apply { kept.forEach(::add) })
            if (!json.has("replace")) json.addProperty("replace", false)
            file.parentFile.mkdirs()
            file.writeText(GSON.toJson(json) + "\n")
        }
    }

    /** What a derived word's ink tag is written down as today, or null where nothing tags it. */
    fun inkTagOn(id: String, where: String): String? =
        listOf("masterwork", "fine").firstOrNull { tier ->
            val file = art.parentFile.resolve("tags/$where/requires_${tier}_ink.json")
            file.isFile && JsonParser.parseString(file.readText()).asJsonObject
                .getAsJsonArray("values")?.any { it.asString == id } == true
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

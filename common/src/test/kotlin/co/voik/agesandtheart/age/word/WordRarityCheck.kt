package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.age.language.Word
import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus.vocabulary
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.RegistryAccess
import net.minecraft.resources.Identifier
import net.minecraft.util.RandomSource
import net.minecraft.world.item.Rarity
import java.io.File

/**
 * How hard a page is to find: a bucket is rolled by weight, then a word within it, and every word is in
 * exactly one bucket — the one listing it, or the default. A different axis from tier, which is what a
 * word costs to write.
 */
@Tags(NEEDS_REGISTRIES)
class WordRarityCheck : FunSpec({

    val ore by lazy { vocabulary.word("minecraft:diamond_ore") ?: error("no word for minecraft:diamond_ore") }
    val authored by lazy { vocabulary.authoredWords.first() }

    fun commonAndRare(rare: Set<String>, rareWeight: Double = 1.0) = WordRarity(
        listOf(
            RarityBucket("common", 1.0, emptySet(), isDefault = true),
            RarityBucket("rare", rareWeight, rare, isDefault = false),
        ),
    )

    /**
     * A name no word answers to is matched by nothing, so the word it meant falls silently to the default bucket. A
     * block of ours is a word only once it is registered, which offline it never is, so its name is held to our
     * language file instead.
     */
    test("every word a shipped bucket lists is a word") {
        val ourBlocks = JsonParser.parseString(File(LANGUAGE_FILE).readText()).asJsonObject.keySet()
            .filter { it.startsWith(OUR_BLOCK_KEY) }.map { "agesandtheart:" + it.removePrefix(OUR_BLOCK_KEY) }.toSet()
        fun isAWord(name: String) = vocabulary.word(name) != null || vocabulary.awaitsAServer(name) || name in ourBlocks
        val unknown = vocabulary.rarity.buckets.flatMap { bucket ->
            bucket.words.filterNot(::isAWord).map { "${bucket.name}: $it" }
        }
        check(unknown.isEmpty()) { "${unknown.size} bucket entries name no word: ${unknown.joinToString(", ")}" }
    }

    test("the shipped buckets have exactly one default") {
        val defaults = vocabulary.rarity.buckets.filter { it.isDefault }
        check(defaults.size == 1) { "expected one default bucket, found ${defaults.map { it.name }}" }
    }

    /** A page's name is coloured by its bucket: white, yellow, aqua and purple, commonest first (Jonah). */
    test("each shipped bucket colours its pages as vanilla colours its rarities") {
        val coloured = vocabulary.rarity.buckets.associate { it.name to it.itemRarity }
        val wanted = mapOf(
            "common" to Rarity.COMMON,
            "staple" to Rarity.UNCOMMON,
            "critical" to Rarity.UNCOMMON,
            "uncommon" to Rarity.RARE,
            "rare" to Rarity.EPIC,
        )
        check(coloured == wanted) { "the buckets colour their pages $coloured" }
    }

    /** Authored and derived alike: nothing about where a word came from decides its rarity. */
    test("an unlisted word is in the default bucket, whichever half of the corpus it is from") {
        val rarity = commonAndRare(rare = emptySet())
        check(rarity.bucketOf(ore)?.name == "common") { "an unlisted derived word is in ${rarity.bucketOf(ore)?.name}" }
        check(rarity.bucketOf(authored)?.name == "common") {
            "an unlisted authored word is in ${rarity.bucketOf(authored)?.name}"
        }
    }

    /** A derived word may be listed by its full id, which is how the authoring tool names one. */
    test("a word listed by its full id is in that bucket and gone from the default") {
        val rarity = commonAndRare(rare = setOf(ore.id.toString()))
        check(rarity.bucketOf(ore)?.name == "rare") { "'${ore.id}' is listed rare and reads ${rarity.bucketOf(ore)?.name}" }
        val drawnAsCommon = rarity.draw(vocabulary, RandomSource.create(SEED), only = setOf("common")) { it == ore }
        check(drawnAsCommon == null) { "'${ore.id}' is listed rare and was still drawn as common" }
    }

    /**
     * **A bucket's share is its weight, whatever it lists.** The default holds nearly the whole corpus
     * and the rare bucket one word, and each still comes up by weight alone.
     */
    test("a bucket's share of pages does not depend on how many words it lists") {
        val rarity = commonAndRare(rare = setOf(ore.name), rareWeight = RARE_WEIGHT)
        val random = RandomSource.create(SEED)
        val rare = (1..DRAWS).count { rarity.draw(vocabulary, random) == ore }
        val share = rare.toDouble() / DRAWS
        val expected = RARE_WEIGHT / (1.0 + RARE_WEIGHT)
        check(share in (expected - TOLERANCE)..(expected + TOLERANCE)) {
            "one rare word at weight $RARE_WEIGHT against the whole corpus came up $share of the time, not ~$expected"
        }
    }

    /** How a secret room asks for its rare pages. */
    test("a draw narrowed to one bucket draws only from it") {
        val listed = vocabulary.authoredWords.take(RARE_COUNT).map { it.name }.toSet()
        val rarity = commonAndRare(rare = listed)
        val random = RandomSource.create(SEED)
        val strays = (1..DRAWS).mapNotNull { rarity.draw(vocabulary, random, only = setOf("rare")) }
            .filter { it.name !in listed }
        check(strays.isEmpty()) { "asked for rare and got ${strays.map { it.name }.distinct()}" }
    }

    /** A narrowed roll whose bucket the filter empties gives nothing, rather than something else. */
    test("a bucket emptied by the filter is skipped, not substituted") {
        val rarity = commonAndRare(rare = setOf(ore.name))
        val drawn = rarity.draw(vocabulary, RandomSource.create(SEED), only = setOf("rare")) { it != ore }
        check(drawn == null) { "the only rare word was filtered out and '${drawn?.name}' came instead" }
    }

    /** An authored word names nothing to tag, so it is kept out of loot by name. */
    test("an authored word on the list is kept out of loot, and one off it is not") {
        val listing = vocabulary.copy(rarity = vocabulary.rarity.copy(keptOutOfLoot = setOf(authored.name)))
        check(CannotAppearInLoot.keepsOut(authored, listing, RegistryAccess.EMPTY)) {
            "'${authored.name}' is on the list and was not kept out"
        }
        val other = vocabulary.authoredWords.first { it != authored }
        check(!CannotAppearInLoot.keepsOut(other, listing, RegistryAccess.EMPTY)) {
            "'${other.name}' is not on the list and was kept out"
        }
    }
    /** The finder's own pages: no duplicate from a bucket that prefers the unknown until they know all of it. */
    test("a bucket that prefers the unknown draws what the finder lacks, and anything once they lack nothing") {
        val three = vocabulary.authoredWords.filter { word -> vocabulary.words.count { it.name == word.name } == 1 }.take(3)
        val (known, alsoKnown, unknown) = three
        fun rarityWith(prefers: Boolean) = WordRarity(
            listOf(
                RarityBucket("common", 1.0, emptySet(), isDefault = true),
                RarityBucket("critical", 1.0, three.map { it.name }.toSet(), isDefault = false, prefersTheUnknown = prefers),
            ),
        )
        val learned = setOf(known, alsoKnown)
        fun drawn(rarity: WordRarity, isNew: (Word) -> Boolean) = (1L..200L).map { seed ->
            rarity.draw(vocabulary, RandomSource.create(seed), only = setOf("critical"), isNew = isNew)
        }.toSet()

        val preferring = drawn(rarityWith(prefers = true)) { it !in learned }
        check(preferring == setOf(unknown)) { "a finder lacking only '${unknown.name}' was handed ${preferring.map { it?.name }}" }

        val allKnown = drawn(rarityWith(prefers = true)) { false }
        check(allKnown.size == three.size) { "a finder who knew all three only ever got ${allKnown.map { it?.name }}" }

        val indifferent = drawn(rarityWith(prefers = false)) { it !in learned }
        check(indifferent.size == three.size) { "a bucket that does not prefer the unknown still did: ${indifferent.map { it?.name }}" }
    }

    /** The library's shelf page is still worth taking once every critical word is known. */
    test("a finder who knows every critical word is handed an uncommon one instead, never a rare one") {
        val critical = vocabulary.rarity.buckets.first { it.name == "critical" }
        check(critical.onceAllKnown == setOf("uncommon")) { "the critical bucket hands on to ${critical.onceAllKnown}" }
        val handedOn = (1L..200L).mapNotNull { seed ->
            vocabulary.rarity.draw(vocabulary, RandomSource.create(seed), only = setOf("critical"), isNew = { false })
        }
        val buckets = handedOn.mapNotNull { vocabulary.rarity.bucketOf(it)?.name }.toSet()
        check(handedOn.size == 200 && buckets.isNotEmpty() && buckets.all { it in critical.onceAllKnown }) {
            "a finder who knew every critical word was handed words from $buckets (${handedOn.size} of 200 draws)"
        }
    }

    test("only the critical bucket prefers the unknown, and electromagnetic is not in it") {
        val preferring = vocabulary.rarity.buckets.filter { it.prefersTheUnknown }.map { it.name }
        check(preferring == listOf("critical")) { "the buckets preferring the unknown are $preferring" }
        val critical = vocabulary.rarity.buckets.first { it.name == "critical" }
        check("electromagnetic" !in critical.words) { "electromagnetic is still critical" }
    }

    /**
     * Masterwork ink is a tag and rarity a hand-kept list, so they drift: an unlisted word is common, and common words
     * fill notebooks. Read off the shipped files, since offline registries carry no tags.
     */
    test("a word only masterwork ink writes is never common, nor in a notebook, unless kept out of loot") {
        val resources = MinecraftRegistries.shippedData()
        fun idsIn(json: JsonObject, field: String) = json.getAsJsonArray(field).map { entry ->
            if (entry.isJsonObject) entry.asJsonObject["id"].asString else entry.asString
        }
        fun idsInFiles(directory: String, name: String, field: String) =
            resources.listResources(directory) { it.path.endsWith("/$name.json") || it.path == "$directory/$name.json" }
                .values.flatMap { resource -> idsIn(resource.openAsReader().use(JsonParser::parseReader).asJsonObject, field) }
        fun shipped(path: String): JsonObject {
            val resource = resources.getResource(Identifier.fromNamespaceAndPath("agesandtheart", path)).orElseThrow()
            return resource.openAsReader().use(JsonParser::parseReader).asJsonObject
        }
        val masterwork = idsInFiles("tags", "requires_masterwork_ink", "values") + idsIn(shipped("art/ink/masterwork.json"), "words")
        val keptOut = (idsInFiles("tags", "cannot_appear_in_loot", "values") + idsIn(shipped("art/cannot_appear_in_loot.json"), "words")).toSet()
        val notebookDraws = shipped("loot_table/inject/notebook.json").getAsJsonArray("pools").flatMap { pool ->
            pool.asJsonObject.getAsJsonArray("entries").flatMap { it.asJsonObject.getAsJsonArray("modifier") }
        }.flatMap { idsIn(it.asJsonObject, "rarity") }.toSet()
        fun bucketListing(id: String) = vocabulary.rarity.buckets.firstOrNull { id in it.words }
        fun isFoundTooEasily(id: String): Boolean {
            val bucket = bucketListing(id)
            return bucket == null || bucket.isDefault || bucket.name in notebookDraws
        }
        check(masterwork.isNotEmpty() && notebookDraws.isNotEmpty()) { "read no masterwork words, or no notebook buckets" }
        val tooEasy = masterwork.filter { it !in keptOut && !it.startsWith(TAG_REFERENCE) }.filter(::isFoundTooEasily)
        check(tooEasy.isEmpty()) {
            "${tooEasy.size} word(s) need masterwork ink and are common or notebook words: ${tooEasy.joinToString(", ")}"
        }
    }
}) {
    private companion object {
        const val SEED = 1L
        const val DRAWS = 4000
        const val RARE_WEIGHT = 0.25
        const val TOLERANCE = 0.03
        const val RARE_COUNT = 5
        const val TAG_REFERENCE = "#"
        const val LANGUAGE_FILE = "src/main/resources/assets/agesandtheart/lang/en_us.json"
        const val OUR_BLOCK_KEY = "block.agesandtheart."
    }
}

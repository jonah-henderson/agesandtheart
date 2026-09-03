package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.ResourceManager

/**
 * What a village writer sells, read the way a server would.
 *
 * **The pools are the thing worth checking**, because both halves fail silently. An authored word listed
 * under a name nothing defines simply never appears; a block tagged into a pool that the Art gives no word
 * to is a member of a set the draw can never reach. Either way a trade quietly hands out fewer words than
 * it was written to, and nothing says so.
 *
 * The tag half is asserted **against the tag files rather than against a resolved pool**: datapack tags
 * are not bound offline, so [WriterStock.words] here would see only the authored names. Reading the files
 * is the same question asked one step earlier, and it is the step where a typo lives.
 */
@Tags(NEEDS_REGISTRIES)
class WriterStockCheck : FunSpec({

    val resources: ResourceManager by lazy { MinecraftRegistries.shippedData() }
    val vocabulary by lazy { Vocabulary.load(resources, MinecraftRegistries.worldgen) }

    /** `writer_stock/apprentice` and its two siblings, from whichever half of the pool declares them. */
    val pools = listOf("apprentice", "expert", "master")

    fun listedWords(pool: String): List<String> {
        val file = Identifier.fromNamespaceAndPath("agesandtheart", "art/writer_stock/$pool.json")
        val resource = resources.getResource(file).orElse(null)
        checkNotNull(resource) { "$file is missing — every pool names its authored half, even when empty" }
        val json = resource.openAsReader().use(JsonParser::parseReader).asJsonObject
        return json.getAsJsonArray("words").map { it.asString }
    }

    fun taggedBlocks(pool: String): List<String> {
        val file = Identifier.fromNamespaceAndPath(
            "agesandtheart", "tags/block/writer_stock/$pool.json",
        )
        val resource = resources.getResource(file).orElse(null) ?: return emptyList()
        val json = resource.openAsReader().use(JsonParser::parseReader).asJsonObject
        return json.getAsJsonArray("values").map { it.asString }
    }

    test("every authored word a pool lists is a word") {
        for (pool in pools) {
            val missing = listedWords(pool).filter { vocabulary.word(it) == null }
            check(missing.isEmpty()) {
                "art/writer_stock/$pool.json lists ${missing.size} word(s) the corpus does not define: " +
                    missing.joinToString(", ")
            }
        }
    }

    test("every block a pool tags is a block") {
        for (pool in pools) {
            val unknown = taggedBlocks(pool).filterNot { id ->
                BuiltInRegistries.BLOCK.containsKey(Identifier.parse(id))
            }
            check(unknown.isEmpty()) {
                "tags/block/writer_stock/$pool.json names ${unknown.size} block(s) that do not exist: " +
                    unknown.joinToString(", ")
            }
        }
    }

    /**
     * The subtler half. A block exists and is still not *sayable*: [DerivedWords] gives a word only to a
     * block an Age could be made of, so tagging a block entity or anything short of a full cube puts a
     * member in the pool that no draw can ever return.
     */
    test("every block a pool tags has a word of its own") {
        for (pool in pools) {
            val wordless = taggedBlocks(pool).filter { vocabulary.word(it) == null }
            check(wordless.isEmpty()) {
                "tags/block/writer_stock/$pool.json tags ${wordless.size} block(s) the Art gives no word " +
                    "to, so a page can never carry them: ${wordless.joinToString(", ")}"
            }
        }
    }

    test("no pool is empty") {
        for (pool in pools) {
            val held = listedWords(pool).size + taggedBlocks(pool).size
            check(held > 0) { "the $pool pool holds nothing, so its trade would hand over a blank page" }
        }
    }

    /**
     * The five sets [co.voik.agesandtheart.desk.WriterProfession] names, and the trades they name in turn.
     * A trade set pointing at a file that is not there is a debug line on the server and a level that
     * silently offers nothing.
     */
    test("every level's trade set exists and names trades that exist") {
        for (level in 1..5) {
            val setFile = Identifier.fromNamespaceAndPath("agesandtheart", "trade_set/writer_level_$level.json")
            val resource = resources.getResource(setFile).orElse(null)
            checkNotNull(resource) { "$setFile is missing — the profession names one trade set per level" }
            val json: JsonObject = resource.openAsReader().use(JsonParser::parseReader).asJsonObject
            val named = json.getAsJsonArray("trades").map { it.asString }
            check(named.isNotEmpty()) { "$setFile names no trades" }

            val shown = json.get("amount").asInt
            check(shown <= named.size) {
                "$setFile shows $shown trades of ${named.size}, so a writer would be asked for more than " +
                    "the level has to give"
            }

            for (trade in named) {
                val id = Identifier.parse(trade)
                val file = Identifier.fromNamespaceAndPath(id.namespace, "villager_trade/${id.path}.json")
                check(resources.getResource(file).isPresent) { "$setFile names $trade, and $file is not there" }
            }
        }
    }

    /** A trade drawing from a pool by a name nothing declares would hand over an unwritten page. */
    test("every pool a trade names is one that exists") {
        val trades = resources.listResources("villager_trade") { it.path.endsWith(".json") }
        check(trades.isNotEmpty()) { "no villager trades were found at all — did the data directory move?" }
        for ((file, resource) in trades) {
            val json = resource.openAsReader().use(JsonParser::parseReader).asJsonObject
            val modifiers = json.getAsJsonArray("given_item_modifiers") ?: continue
            for (modifier in modifiers) {
                val named = modifier.asJsonObject.get("pool")?.asString ?: continue
                val pool = Identifier.parse(named).path.removePrefix("writer_stock/")
                check(pool in pools) { "$file draws from '$named', which no pool declares" }
            }
        }
    }
})

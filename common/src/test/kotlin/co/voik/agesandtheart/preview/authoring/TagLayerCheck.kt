package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.aspect.Aspect
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * The tag layer as the word forge shows it.
 *
 * Two halves, and they are checked for opposite reasons. The edits are pure transformations of a table
 * and are checked because **every one of them has an inverse somebody will expect to work** - set a
 * weight and clear it, drop a tag and put it back - and an editor that cannot undo itself is one nobody
 * will trust with a corpus.
 *
 * The merge is checked because the screen computes it a second time. `Vocabulary` flattens the derived
 * and authored halves at load and the forge has to hold them apart to say which is which, so it merges
 * them itself; if the two ever disagree the screen is quietly lying about the world.
 */
@Tags(NEEDS_REGISTRIES)
class TagLayerCheck : FunSpec({

    fun table(text: String) = JsonParser.parseString(text).asJsonObject

    test("a weight set is a weight the table states") {
        val after = TagFile.withWeight(JsonObject(), "canyon", "eroded", 0.6)
        check(after.getAsJsonObject("canyon").getAsJsonObject("tags").get("eroded").asDouble == 0.6) {
            "wrote $after"
        }
    }

    test("a weight cleared takes the entry with it") {
        val before = table("""{"canyon": {"tags": {"eroded": 1.0}}}""")
        val after = TagFile.withWeight(before, "canyon", "eroded", null)
        check(!after.has("canyon")) { "an entry saying nothing was left behind: $after" }
    }

    /**
     * The inverse that matters most: an entry with a `readiness` is not empty once its last tag goes, and
     * tidying it away would silently change how willingly the Art reaches for that preset.
     */
    test("clearing the last tag keeps an entry that still says something else") {
        val before = table("""{"canyon": {"readiness": 0.5, "tags": {"eroded": 1.0}}}""")
        val after = TagFile.withWeight(before, "canyon", "eroded", null)
        check(after.getAsJsonObject("canyon")?.get("readiness")?.asDouble == 0.5) { "lost the readiness: $after" }
    }

    test("a tag dropped and restored leaves the table as it was") {
        val dropped = TagFile.withDropped(JsonObject(), "badlands", "lush", dropped = true)
        check(dropped.getAsJsonObject("badlands").getAsJsonArray("drop").map { it.asString } == listOf("lush")) {
            "dropped wrongly: $dropped"
        }
        val restored = TagFile.withDropped(dropped, "badlands", "lush", dropped = false)
        check(restored.entrySet().isEmpty()) { "restoring left something behind: $restored" }
    }

    test("a rename moves the weight, the drop and the query alike") {
        val tags = table("""{"canyon": {"tags": {"brilliant": 0.8}, "drop": ["brilliant"]}}""")
        check(TagFile.renameInTable(tags, "brilliant", "bright")) { "said it changed nothing" }
        val entry = tags.getAsJsonObject("canyon")
        check(entry.getAsJsonObject("tags").get("bright").asDouble == 0.8) { "weight did not move: $tags" }
        check(entry.getAsJsonArray("drop").map { it.asString } == listOf("bright")) { "drop did not move: $tags" }

        val word = table("""{"query": {"brilliant": 1.0}, "requests": {"queries": {"sky": {"brilliant": -0.5}}}}""")
        check(TagFile.renameInWord(word, "brilliant", "bright")) { "said the word did not mention it" }
        check(word.getAsJsonObject("query").get("bright").asDouble == 1.0) { "flat query did not move: $word" }
        val leaning = word.getAsJsonObject("requests").getAsJsonObject("queries").getAsJsonObject("sky")
        // The sign is the whole meaning of a pushed tag, so it travels with the name.
        check(leaning.get("bright").asDouble == -0.5) { "a requested query lost its sign: $word" }
    }

    test("a rename onto a tag already there takes the stronger claim") {
        val tags = table("""{"canyon": {"tags": {"sweeping": 0.4, "colossal": 0.9}}}""")
        TagFile.renameInTable(tags, "sweeping", "colossal")
        val kept = tags.getAsJsonObject("canyon").getAsJsonObject("tags")
        check(kept.get("colossal").asDouble == 0.9) { "took the weaker claim: $tags" }
        check(!kept.has("sweeping")) { "left the old name behind: $tags" }
    }

    test("a rename to a name nothing carries reports touching nothing") {
        check(!TagFile.renameInTable(table("""{"canyon": {"tags": {"dry": 1.0}}}"""), "lush", "verdant"))
    }

    /**
     * **The screen and the corpus have to agree**, preset for preset and tag for tag.
     *
     * This is the check the whole tag view rests on. `Vocabulary.load` merges the derivation and the
     * authored overlay once and keeps only the answer; the forge runs the same two calls and merges them
     * again so it can say which half a weight came from. Nothing stops the second merge drifting from
     * the first except this.
     */
    test("what the tag layer says a preset carries is what the corpus says") {
        val corpus = Corpus.load()
        val layer = TagLayer(corpus)
        val mine = mutableMapOf<Pair<Aspect, String>, MutableMap<String, Double>>()
        for (fact in layer.facts()) {
            for (carrier in layer.carriersOf(fact.tag)) {
                if (carrier.source == TagLayer.Source.DROPPED) continue
                mine.getOrPut(carrier.aspect to carrier.preset) { mutableMapOf() }[fact.tag] = carrier.weight
            }
        }
        val wrong = Aspect.entries.flatMap { aspect ->
            corpus.vocabulary.candidatesFor(aspect).mapNotNull { preset ->
                val theirs = corpus.vocabulary.tagsOf(preset)
                val ours = mine[aspect to preset.key].orEmpty()
                if (ours == theirs) null else "${aspect.page}/${preset.key}: forge $ours, corpus $theirs"
            }
        }
        check(wrong.isEmpty()) { "${wrong.size} presets disagree:\n" + wrong.take(10).joinToString("\n") }
    }

    /** A tag nothing carries and nothing asks for is not a tag; the list should never invent one. */
    test("every tag listed is carried, asked for, or opposed") {
        val corpus = Corpus.load()
        val layer = TagLayer(corpus)
        val opposed = corpus.vocabulary.antonyms.flatMap { listOf(it.first, it.second) }.toSet()
        val idle = layer.facts().filter { it.carriers == 0 && it.asked == 0 && it.tag !in opposed }
        check(idle.isEmpty()) { "listed for no reason: ${idle.map { it.tag }}" }
    }
})

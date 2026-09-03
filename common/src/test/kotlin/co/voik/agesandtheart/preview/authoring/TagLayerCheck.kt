package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.aspect.Aspect
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * The tag layer as Scrivener shows it.
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

    test("a rename moves the weight and the drop alike") {
        val tags = table("""{"canyon": {"tags": {"brilliant": 0.8}, "drop": ["brilliant"]}}""")
        check(TagFile.renameInTable(tags, "brilliant", "bright")) { "said it changed nothing" }
        val entry = tags.getAsJsonObject("canyon")
        check(entry.getAsJsonObject("tags").get("bright").asDouble == 0.8) { "weight did not move: $tags" }
        check(entry.getAsJsonArray("drop").map { it.asString } == listOf("bright")) { "drop did not move: $tags" }
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
            for (member in layer.membersTagged(fact.tag)) {
                if (member.source == TagLayer.Source.DROPPED) continue
                mine.getOrPut(member.aspect to member.preset) { mutableMapOf() }[fact.tag] = member.weight
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
        val idle = layer.facts().filter { it.members == 0 && it.asked == 0 && it.tag !in opposed }
        check(idle.isEmpty()) { "listed for no reason: ${idle.map { it.tag }}" }
    }

    /**
     * **Tagging something new and putting it back leaves the file as it was.**
     *
     * The inverse the file's own KDoc claims for every edit, asked of the one that creates an entry from
     * nothing: a member with no line gets one, and clearing the weight has to take the whole entry away
     * rather than leave `{"tags": {}}` behind.
     */
    test("tagging a member that had no entry, and untagging it, is a round trip") {
        val table = JsonParser.parseString("""{"minecraft:jungle": {"tags": {"lush": 1.0}}}""").asJsonObject
        val was = table.toString()
        val tagged = TagFile.withWeight(table, "minecraft:badlands", "dry", 1.0)
        check(tagged.getAsJsonObject("minecraft:badlands") != null) { "no entry was made: $tagged" }
        val back = TagFile.withWeight(tagged, "minecraft:badlands", "dry", null)
        check(back.toString() == was) { "adding and undoing left $back, where it began $was" }
    }

    /**
     * **What may be tagged is what the aspect can hold, less what already carries it.**
     *
     * A closed aspect offers the presets this pack wrote; an open one offers what the corpus knows,
     * which is what its derived words choose. Offering something already tagged would be offering a
     * second weight for one member, which the file cannot hold.
     */
    test("what is offered to tag excludes what already carries the tag") {
        val layer = TagLayer(Corpus.load())
        val tagged = layer.membersTagged("cavernous").filter { it.aspect == Aspect.CARVERS }.map { it.preset }
        check(tagged.isNotEmpty()) { "nothing in the rock carries #cavernous, so this checks nothing" }
        val offered = layer.untaggedIn(Aspect.CARVERS, "cavernous").map { it.preset }
        check(offered.none { it in tagged }) { "it offered something already tagged: $offered" }
        val everything = Aspect.CARVERS.authored.map { it.key }
        check((offered + tagged).toSet() == everything.toSet()) {
            "offered and tagged should be the whole aspect: ${offered + tagged} against $everything"
        }
    }


    /**
     * **A rename reaches every place a word spells a tag** — and until 2026-09-02 it reached none of them.
     *
     * It read `query` and `queries`, which no word has carried since the world model landed, so a rename
     * moved the tables and the antonyms and left every word asking for the old name: legal, silent, and
     * finding nothing. The three places are `restricts` (bare), `biases` and `excludes` (marked), and the
     * mark is what tells a tag from a member where both are legal.
     */
    test("a rename reaches restricts, biases and excludes") {
        val word = JsonParser.parseString(
            """
            {
              "restricts": {"biomes": {"frozen": 1.0}},
              "biases": {"all": {"#frozen": -0.4, "minecraft:jungle": 0.5}},
              "excludes": {"sea": ["#frozen", "minecraft:water"]}
            }
            """.trimIndent(),
        ).asJsonObject
        check(TagFile.renameInWord(word, "frozen", "icy")) { "it found nothing to rename" }
        val said = word.toString()
        check("frozen" !in said) { "something still says frozen: $said" }
        check(""""icy":1.0""" in said) { "the bare weight did not move: $said" }
        check(""""#icy":-0.4""" in said) { "the marked weight did not move, or lost its sign: $said" }
        check("\"#icy\"" in said && "minecraft:water" in said) { "the exclusion did not move: $said" }
    }

    /** And a deletion reaches the same three, leaving the members alone. */
    test("a deletion takes a tag out of every place a word spells it") {
        val word = JsonParser.parseString(
            """
            {
              "restricts": {"biomes": {"frozen": 1.0, "lush": 0.5}},
              "biases": {"all": {"#frozen": -0.4, "minecraft:jungle": 0.5}},
              "excludes": {"sea": ["#frozen"]}
            }
            """.trimIndent(),
        ).asJsonObject
        check(TagFile.forgetInWord(word, "frozen")) { "it found nothing to forget" }
        val said = word.toString()
        check("frozen" !in said) { "something still says frozen: $said" }
        check("lush" in said && "minecraft:jungle" in said) { "it took something else with it: $said" }
        // An exclusion list with nothing left in it goes, rather than sitting there striking nothing.
        check("sea" !in said) { "an emptied exclusion was left behind: $said" }
    }

})

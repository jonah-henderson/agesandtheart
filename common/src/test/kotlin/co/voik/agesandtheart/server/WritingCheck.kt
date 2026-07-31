package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * Writing Ages out of words, on a real server — which is the only place the vocabulary is whole.
 *
 * **Offline the corpus is blocks and the authored words.** Biomes and structure sets are derived from the
 * *dynamic* registries, so `villages` and `jungle` do not exist until a server has loaded its datapacks —
 * and with them go the populative half of the language and everything `only`, `except` and the rungs act on.
 * That is the gap these fill, and it is why they are worth minutes.
 *
 * Each asks the command for a **document** rather than reading its prose. `/age compare` saying
 * "248,734 block(s) differ" is a sentence; `differingBlocks` is a number, and a check that reads the
 * sentence is really asserting on the wording.
 */
@Tags(NEEDS_SERVER)
class WritingCheck : FunSpec({
    val server = DrivenServer.shared

    /**
     * The readout has to show where each page landed, and two aiming pages means two sections — the
     * failure it exists to catch is a material attaching to the wrong one.
     */
    test("the readout shows what was aimed where") {
        val written = server.ask("write", "readsback landmass floating basalt sea molten lava")
        val readout = written.get("readout").asString
        check(readout == "landmass floating of basalt, over sea molten of lava.") {
            "the sections blurred: '$readout'"
        }
    }

    /** Without an aiming page in front of them the same words are one section, not two. */
    test("a book that aims at nothing is one section") {
        val written = server.ask("write", "unaimed floating basalt")
        check(written.get("readout").asString == "floating of basalt.") {
            "an unaimed book read back as '${written.get("readout").asString}'"
        }
    }

    /**
     * **It prettifies, it never launders** (§4.3.1): an unreadable page is absent from the prose and
     * present in `dropped`. Nice prose hiding a misparse is the failure rejecting ambiguity exists to
     * prevent.
     */
    test("an unread page is reported and never laundered") {
        val written = server.ask("write", "laundered landmass zzzznotaword basalt")
        val dropped = written.getAsJsonArray("dropped").map { it.asString }
        check(dropped == listOf("zzzznotaword")) { "reported $dropped as unread" }
        check("zzzznotaword" !in written.get("readout").asString) {
            "an unreadable page reached the prose: '${written.get("readout").asString}'"
        }
    }

    /**
     * The quantifier, against the population it was built for. `teeming villages` was `/age compose`-only
     * until the production landed, and `villages` exists only here.
     */
    test("a rung reaches a population") {
        for ((rung, name) in listOf("teeming" to "manyvillages", "scarce" to "fewvillages")) {
            server.ask("write", "$name structures $rung villages")
            val recipe = recipeOf(server, name)
            check("minecraft:villages@$rung" in recipe) { "'$rung villages' wrote $recipe" }
        }
    }

    /** A rung binds to one term. Joined with another value, only the quantified one carries it. */
    test("a rung counts only the term it precedes") {
        server.ask("write", "onlyoneteems structures woodland_mansions and teeming villages")
        val recipe = recipeOf(server, "onlyoneteems")
        check("minecraft:villages@teeming" in recipe) { "the rung did not reach its own term: $recipe" }
        check("woodland_mansions@" !in recipe) { "the rung leaked onto the term beside it: $recipe" }
    }

    /** `only` and a rung are independent axes on one value, and must not eat each other. */
    test("only and a rung stack on one value") {
        server.ask("write", "onlyteeming structures only teeming villages")
        val recipe = recipeOf(server, "onlyteeming")
        check("!minecraft:villages@teeming" in recipe) { "'only teeming villages' wrote $recipe" }
    }

    /**
     * The three knobs that were pinned into recipes and unreachable from a sentence. Each is now a word,
     * which is the whole of what the Phase 4 remainder owed here.
     */
    test("the pinned knobs can be written") {
        val knobs = listOf(
            Triple("finemingle", "landmass finely basalt and slate", "mingling=fine"),
            Triple("bareskin", "biomes bare", "skin=bare"),
            Triple("highislands", "landmass floating aloft", "altitude=high"),
        )
        for ((name, sentence, expected) in knobs) {
            server.ask("write", "$name $sentence")
            val recipe = recipeOf(server, name)
            check(expected in recipe) { "'$sentence' should have written $expected, and wrote $recipe" }
        }
    }
})

/** The recipe an Age was written with, read back out of `/age list`. */
private fun recipeOf(server: DrivenServer, name: String): String {
    val listed = server.ask("list")
    val age = listed.getAsJsonArray("ages").map { it.asJsonObject }
        .firstOrNull { it.get("age").asString.endsWith(":$name") }
        ?: error("no Age called '$name' in ${listed.getAsJsonArray("ages")}")
    return age.get("recipe").asString
}

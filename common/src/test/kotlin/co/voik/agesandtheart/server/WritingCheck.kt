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
 *
 * **Every sentence here starts with `age`, and it has to.** The nucleus is mandatory (`Art.g4`), so a book
 * without one is not a sentence and is filled in against one the Art draws for itself — which keeps the
 * writer's content pages and silently drops the structural ones, `only` and the rungs among them. These
 * checks were written before that rule and asserted on repaired books for a while: `teeming villages` came
 * back as plain `villages`, and the assertion that the rung had reached the recipe was the only thing that
 * noticed. A sentence here that stops parsing does not fail loudly; it quietly starts testing repair.
 */
@Tags(NEEDS_SERVER)
class WritingCheck : FunSpec({
    val server = DrivenServer.shared

    /**
     * The readout has to show where each page landed, and two aiming pages means two sections — the
     * failure it exists to catch is a material attaching to the wrong one.
     */
    test("the readout shows what was aimed where") {
        val written = server.ask("write", "readsback age landmass floating basalt sea molten lava")
        val readout = written.get("readout").asString
        check(readout == "age: landmass floating of basalt, over sea molten of lava.") {
            "the sections blurred: '$readout'"
        }
        check(written.getAsJsonArray("supplied").isEmpty) {
            "the book did not parse and was filled in: ${written.getAsJsonArray("supplied")}"
        }
    }

    /** Without an aiming page in front of them the same words are the nucleus, not a section of their own. */
    test("a book that aims at nothing is one section") {
        val written = server.ask("write", "unaimed age floating basalt")
        check(written.get("readout").asString == "age: floating of basalt.") {
            "an unaimed book read back as '${written.get("readout").asString}'"
        }
    }

    /**
     * **A page the Art never heard of is a typo, and the pen says so** — it does not make a vaguer Age.
     * A page is a physical item carrying a real word, so an unknown one cannot have been written; the pen
     * never refusing (design §2) is about books that do not *parse*, which repair handles, not about words
     * that do not exist.
     *
     * The command's own contract, which is why it is here: offline `RepairCheck` drives `Grammar.read`
     * directly and never sees the refusal.
     */
    test("a page the Art never heard of is refused") {
        val written = server.ask("write", "laundered age landmass zzzznotaword basalt")
        val complaint = written.get("error")?.asString
        check(complaint != null && "zzzznotaword" in complaint) {
            "an unknown page was not refused by name: $written"
        }
        check(written.get("age") == null) { "an Age was written from a book with an unknown page: $written" }
    }

    /**
     * The quantifier, against the population it was built for. `teeming villages` was `/age compose`-only
     * until the production landed, and `villages` exists only here.
     */
    test("a rung reaches a population") {
        for ((rung, name) in listOf(TEEMING to "manyvillages", SCARCE to "fewvillages")) {
            server.ask("write", "${name} age structures ${rung.said} villages")
            val recipe = recipeOf(server, name)
            check("minecraft:villages@${rung.written}" in recipe) { "'${rung.said} villages' wrote $recipe" }
        }
    }

    /** A rung binds to one term. Joined with another value, only the quantified one carries it. */
    test("a rung counts only the term it precedes") {
        server.ask("write", "onlyoneteems age structures woodland_mansions and teeming villages")
        val recipe = recipeOf(server, "onlyoneteems")
        check("minecraft:villages@${TEEMING.written}" in recipe) { "the rung did not reach its own term: $recipe" }
        check("woodland_mansions@" !in recipe) { "the rung leaked onto the term beside it: $recipe" }
    }

    /** `only` and a rung are independent axes on one value, and must not eat each other. */
    test("only and a rung stack on one value") {
        server.ask("write", "onlyteeming age structures only teeming villages")
        val recipe = recipeOf(server, "onlyteeming")
        check("!minecraft:villages@${TEEMING.written}" in recipe) { "'only teeming villages' wrote $recipe" }
    }

    /**
     * The knobs that were pinned into recipes and unreachable from a sentence. Each is now a word, which is
     * the whole of what the Phase 4 remainder owed here.
     */
    test("the pinned knobs can be written") {
        val knobs = listOf(
            Triple("finemingle", "age landmass finely basalt and deepslate", "mingling=1..1"),
            Triple("bareskin", "age biomes bare", "skin=bare"),
        )
        for ((name, sentence, expected) in knobs) {
            server.ask("write", "$name $sentence")
            val recipe = recipeOf(server, name)
            check(expected in recipe) { "'$sentence' should have written $expected, and wrote $recipe" }
        }
    }
})

/**
 * A quantifier page and the number it writes — the page is what a book holds and the number is what the
 * recipe does, which is the whole of §3.2's split and the thing these checks are here to see.
 */
private data class Quantifier(val said: String, val written: String)

private val TEEMING = Quantifier("teeming", "4")
private val SCARCE = Quantifier("scarce", "0.25")

/** The recipe an Age was written with, read back out of `/age list`. */
private fun recipeOf(server: DrivenServer, name: String): String {
    val listed = server.ask("list")
    val age = listed.getAsJsonArray("ages").map { it.asJsonObject }
        .firstOrNull { it.get("age").asString.endsWith(":$name") }
        ?: error("no Age called '$name' in ${listed.getAsJsonArray("ages")}")
    return age.get("recipe").asString
}

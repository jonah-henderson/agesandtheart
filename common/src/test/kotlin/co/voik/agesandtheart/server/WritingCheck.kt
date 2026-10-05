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
 * **Every sentence here carries `age`, and it has to.** The nucleus is mandatory and refused outright
 * without it (§4.3.1); a book that has one but does not parse is filled in against a sentence the Art
 * draws for itself — which keeps the
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
        val written = server.ask("write", "readsback age floating basalt landmass molten lava sea")
        val readout = written.get("readout").asString
        check(readout == "age: floating, basalt landmass, over molten, lava sea.") {
            "the sections blurred: '$readout'"
        }
        check(written.getAsJsonArray("supplied").isEmpty) {
            "the book did not parse and was filled in: ${written.getAsJsonArray("supplied")}"
        }
    }

    /**
     * `wondrous` leans every aspect towards glowing, and the derived sea once took any glowing block — a
     * sea of lava cauldrons or of fire, which a client cannot draw. The sea keeps the rock's rule now.
     */
    test("a vague glowing sea is never one a world cannot be made of") {
        val refused = listOf("minecraft:lava_cauldron", "minecraft:fire", "minecraft:soul_fire", "minecraft:frosted_ice")
        val seas = (1..GLOWING_SEA_SEEDS).map { seed ->
            val said = server.run("age write glowingsea$seed $seed wondrous age")
            Regex("""sea=(\S+)""").find(said)?.groupValues?.get(1).orEmpty()
        }
        val wrong = seas.filter { it in refused }
        check(wrong.isEmpty()) { "these seas were written: $wrong, of $seas" }
    }

    /**
     * **Describing or naming asks for more of what is here; `everywhere` asks for the thing.**
     *
     * `trees` reaches some seventy features through a tag. Read as seventy namings it put acacia, bamboo
     * and cherry into every biome at once, and an Age came out uniformly forested with no regard for what
     * grew where. Naming one is read the same way, and `everywhere` is what puts it where it was not.
     */
    test("a described or named feature is bent where it grows, a widened one is put there") {
        val described = server.run("age write describedtrees 7 age teeming trees features")
        check("where_it_grows" in described) {
            "'teeming trees' asked for trees to be *added* everywhere:\n$described"
        }
        val named = server.run("age write namedtree 7 age acacia features")
        check("minecraft:acacia[where_it_grows,introduced_if_absent" in named) {
            "naming a feature put it where it was not:\n$named"
        }
        val widened = server.run("age write widenedtree 7 age acacia everywhere")
        check("minecraft:acacia" in widened && "minecraft:acacia[where_it_grows" !in widened) {
            "'acacia everywhere' did not put it where it was not:\n$widened"
        }
    }

    /** Without an aiming page in front of them the same words are the nucleus, not a section of their own. */
    test("a book that aims at nothing is one section") {
        val written = server.ask("write", "unaimed floating basalt age")
        check(written.get("readout").asString == "age: floating, basalt.") {
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
        val written = server.ask("write", "laundered age zzzznotaword basalt landmass")
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
            server.ask("write", "${name} age ${rung.said} villages structures")
            val recipe = recipeOf(server, name)
            check("minecraft:villages[amount=${rung.written}]" in recipe) { "'${rung.said} villages' wrote $recipe" }
        }
    }

    /** A rung binds to one term. Joined with another value, only the quantified one carries it. */
    test("a rung counts only the term it precedes") {
        server.ask("write", "onlyoneteems age woodland_mansions and teeming villages structures")
        val recipe = recipeOf(server, "onlyoneteems")
        check("minecraft:villages[amount=${TEEMING.written}]" in recipe) { "the rung did not reach its own term: $recipe" }
        check("woodland_mansions[amount=$A_MENTION]" in recipe) { "the rung leaked onto the term beside it: $recipe" }
    }

    /** `only` and a rung are independent axes on one value, and must not eat each other. */
    test("only and a rung stack on one value") {
        server.ask("write", "onlyteeming age only teeming villages structures")
        val recipe = recipeOf(server, "onlyteeming")
        check("minecraft:villages[only,amount=${TEEMING.written}]" in recipe) { "'only teeming villages' wrote $recipe" }
    }

    /**
     * The parameters that were pinned into recipes and unreachable from a sentence. Each is now a word, which is
     * the whole of what the Phase 4 remainder owed here.
     */
    test("the pinned parameters can be written") {
        val parameters = listOf(
            Triple("blotchy", "age patchy basalt and deepslate landmass", "mingling=0.1..0.7"),
            Triple("bareground", "age minecraft:air surface", "surface.material=minecraft:air"),
        )
        for ((name, sentence, expected) in parameters) {
            server.ask("write", "$name $sentence")
            val recipe = recipeOf(server, name)
            check(expected in recipe) { "'$sentence' should have written $expected, and wrote $recipe" }
        }
    }

    /**
     * **What one evocative page may bring about** — the bound on an atmosphere's reach
     * (`Resolver.drawnAmong`), and **the check has to be here**: a tag's reach is only whole on a server,
     * so offline `foreboding` touches a fraction of what it touches in play.
     *
     * The sentence and the seed are a real Age's. Written from a found book, it resolved to **forty-nine
     * creatures and seven phenomena** — in play, hadalfish and ghasts in a basalt world under a tempest, a
     * blizzard, an inferno and a deluge at once (Jonah, 2026-09-17, the Age Tsi). A word meaning dread
     * should make an Age dreadful, which is a few of the right things rather than the catalogue.
     *
     * The other half matters as much: it must still bring *something* about, or the bound has turned an
     * evocative word into a word that does nothing.
     */
    test("one evocative page brings about a few things, not the catalogue") {
        server.ask("write", "dreadful 5134421 foreboding age basalt landmass molten sea")
        val recipe = recipeOf(server, "dreadful")

        val creatures = introducedIn(recipe, "spawns.lives")
        check(creatures.size in SOMETHING..MOST_CREATURES) {
            "'foreboding' introduced ${creatures.size} creatures: $creatures"
        }
        val phenomena = introducedIn(recipe, "phenomena.happens")
        check(phenomena.size in SOMETHING..MOST_PHENOMENA) {
            "'foreboding' introduced ${phenomena.size} phenomena: $phenomena"
        }
    }
})

/**
 * Everything a description **introduces** into one pool — a claim it reached by query and asked more than
 * ordinary of, which is the pair of conditions the consumers read (`Claim.bringsNothingAbout`).
 */
private fun introducedIn(recipe: String, pool: String): List<String> {
    val written = Regex("""\b${Regex.escape(pool)}=(\S+)""").find(recipe)?.groupValues?.get(1) ?: return emptyList()
    return Regex("""([\w:]+)\[where_it_grows,amount=([0-9.]+)]""").findAll(written)
        .filter { it.groupValues[2].toDouble() > ORDINARY }
        .map { it.groupValues[1] }
        .toList()
}

private const val ORDINARY = 1.0

/** At least one, or a bound that brings nothing about would pass as well as a working one. */
private const val SOMETHING = 1

/** `Resolver.INTRODUCES_WHAT_IT_LIFTS`, restated so a change to it is a change here too. */
private const val MOST_CREATURES = 6
private const val MOST_PHENOMENA = 2

/**
 * A quantifier page and the number it writes — the page is what a book holds and the number is what the
 * recipe does, which is the whole of §3.2's split and the thing these checks are here to see.
 */
private data class Quantifier(val said: String, val written: String)

/**
 * What naming one member comes to unquantified: half as much again. A rung replaces it rather than scaling
 * it, so a quantified mention writes exactly the rung — see `Resolver.claimForMember`.
 */
private const val A_MENTION = "1.5"

/** Enough draws that a sea turning up in one in five would show; the leak was worse than that. */
private const val GLOWING_SEA_SEEDS = 16

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

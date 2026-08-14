package co.voik.agesandtheart.server

import co.voik.agesandtheart.age.aspect.Aspect
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * Whether the corpus is *found* when the mod is a jar rather than a source tree — which is the one thing
 * `VocabularyCheck` cannot ask, reading the same files off a directory.
 *
 * The corpus comes from `server.resourceManager` over `data/agesandtheart/art/`, and a wrong path fails by
 * the Art knowing no words at all. So the load-bearing assertion is the count, and the floor is deliberately
 * far below the real number: a pack with more mods pushes it up, and that must never fail.
 */
@Tags(NEEDS_SERVER)
class VocabularyOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    test("the corpus is found and whole") {
        val vocabulary = server.ask("words")
        val words = vocabulary.get("words").asInt
        check(words >= 100) {
            "the Art knows $words words — the corpus was not found where a jar keeps it"
        }
        val problems = vocabulary.getAsJsonArray("problems").map { it.asString }
        check(problems.isEmpty()) { "the corpus would not load: $problems" }
    }

    /**
     * **Every fluid the mod ships is sayable**, which is what `ink springs` needs (world model §8.1.2).
     *
     * The corpus is derived from the **block** registry, so a fluid is reachable only through the block it
     * pours into — and ours were named `<fluid>_block`, where vanilla names a liquid block after its own
     * fluid (`minecraft:water` is both). So the word for our own ink was `ink_block` and the design's
     * headline example named nothing at all.
     *
     * Asked of the server because the loaders register these, not `common`.
     */
    test("the mod's own fluids are words") {
        val vocabulary = server.ask("words")
        val known = vocabulary.getAsJsonArray("authoredWords").map { it.asJsonObject.get("word").asString }.toSet()
        for (ink in listOf("ink", "fine_ink", "masterwork_ink")) {
            val written = server.run("age write inkcheck_$ink 4242 age $ink springs")
            check("of=agesandtheart:$ink" in written) {
                "'$ink springs' did not mint a spring running with it:\n$written"
            }
        }
        check(known.isNotEmpty()) { "the corpus reported no authored words at all" }
    }

    /**
     * **The words a server has and an offline check cannot.** Biomes and structure sets derive from the
     * *dynamic* registries, so their absence offline is expected and their absence here is a bug — and it
     * would silently take `only`, `except` and the rungs with it, since those act on populations.
     */
    test("the derived populations arrive with the registries") {
        val vocabulary = server.ask("words")
        val authored = vocabulary.get("authored").asInt
        val derived = vocabulary.get("derived").asInt
        check(derived > authored * 10) {
            "only $derived derived words against $authored authored — the registries did not reach the corpus"
        }

        // **Named, not counted.** A threshold on the total is a number that drifts with every block added
        // to the pack and says nothing about *which* population is missing; a biome and a structure set
        // being readable is the claim itself. Asserted through the parser, since a word the corpus lacks is
        // reported as a page the Art could not read.
        val written = server.ask("write", "populationsarrived age jungle biomes villages structures")
        val unreadable = written.getAsJsonArray("unreadable").map { it.asString }
        check(unreadable.isEmpty()) {
            "the server's dynamic registries did not reach the corpus — unread: $unreadable"
        }
        // `supplied` is what the Art had to write for itself, and it must be empty: a book that fails to
        // parse is repaired against a drawn sentence and comes back with *nothing* unreadable, so without
        // this the check above passes whether or not either word was ever understood.
        val supplied = written.getAsJsonArray("supplied").map { it.asString }
        check(supplied.isEmpty()) { "the book did not parse as written and was filled in with $supplied" }
    }

    /**
     * **The half of the tag layer only a server has.** Vanilla's own tags are bound here and absent
     * offline, so a rule keyed on `#minecraft:is_ocean` or `#minecraft:ice` does nothing in
     * `TagCoverageCheck` and everything here — which means this is the only place a *renamed* vanilla tag
     * can be caught (`notes/the-tag-layer.md` §4).
     *
     * Two claims. The reach must be **above what the facts alone give**, which is the floor that check
     * ratchets; and `DerivedTags` reports a rule matching nothing as a corpus problem, which the first test
     * above fails the build over. The numbers are the offline ones, so a gap between them *is* the tag
     * half working.
     */
    test("vanilla's own tags reach the corpus") {
        val reach = server.ask("words").getAsJsonArray("reach")
            .associate { it.asJsonObject.get("aspect").asString to it.asJsonObject.get("reachable").asInt }
        // Offline these are 3 and 43, and every one of the difference arrives on a vanilla tag: the ice
        // family as a sea, and every biome whose `is_*` tag says what it is.
        check((reach["sea"] ?: 0) > 3) {
            "no block reached the sea by tag — `#minecraft:ice` and ours matched nothing: $reach"
        }
        check((reach["biomes"] ?: 0) > 43) {
            "no biome gained anything from `#minecraft:is_*` — the tags did not reach the corpus: $reach"
        }
    }

    /**
     * Every aiming page has to be a page a writer can actually lay down, or sections cannot be opened.
     *
     * **Spelled out rather than derived**, which is the point of it: these are the pages a player learns,
     * so an accidental rename should fail here loudly and be answered by moving the vocabulary with it.
     * One per aspect, synthesised from it (world model §3) — where there used to be a domain layer
     * as well as the air, and nothing is aimed at an aspect any more.
     *
     * The minting patterns aim too, because a pattern closes the clause its material qualifies (§8.1.2).
     * They are authored, so they are listed rather than derived.
     */
    test("the aiming pages are in the corpus") {
        val vocabulary = server.ask("words")
        val aiming = vocabulary.getAsJsonArray("authoredWords").map { it.asJsonObject }
            .filter { it.get("aims").asBoolean }
            .map { it.get("word").asString }
        // One per aspect, synthesised rather than authored — `art/domain/` is gone, and with it the
        // layer that let a page cover more than one aspect. `firmament` was that layer's name for the sky.
        // By **page**: a writer's name for the part of the world, which is what an aiming page is. Two
        // aspects are recorded under another name ([Aspect.key]), and a save is the only place that shows.
        val expected = Aspect.entries.map { it.page } + MINTING_PATTERNS
        check(aiming.sorted() == expected.sorted()) { "the aiming pages are $aiming" }
    }
})

/** The patterns a material can be minted from (`art/word/`), which aim without naming an aspect's own page. */
private val MINTING_PATTERNS = listOf("springs", "veins")

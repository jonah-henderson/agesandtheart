package co.voik.agesandtheart.server

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
        val written = server.ask("write", "populationsarrived age biomes jungle structures villages")
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

    /** Every aiming page has to be a page a writer can actually lay down, or sections cannot be opened. */
    test("the aiming pages are in the corpus") {
        val vocabulary = server.ask("words")
        val aiming = vocabulary.getAsJsonArray("authoredWords").map { it.asJsonObject }
            .filter { it.get("aims").asBoolean }
            .map { it.get("word").asString }
        val expected = listOf(
            "landmass", "sea", "depths", "biomes", "surface", "features", "spawns", "sky", "structures",
            "climate",
        )
        check(aiming.sorted() == expected.sorted()) { "the aiming pages are $aiming" }
    }
})

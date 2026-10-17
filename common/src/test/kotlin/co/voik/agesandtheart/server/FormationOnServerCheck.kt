package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * **`gold_block obelisks`, end to end** — the one thing offline cannot answer.
 *
 * A formation's pattern is a placed feature of *ours*, and placed features are a datapack registry: they
 * do not exist until a server has loaded its packs. So `FormationCheck` can prove that a shape is laid and
 * that minting swaps a substance, and it cannot prove that the word reaches the shipped shape at all.
 * Everything between the page a writer lays and the feature an Age grows lives here.
 */
@Tags(NEEDS_SERVER)
class FormationOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    /** What one book left in the features pool, as the recipe holds it. */
    fun grownBy(name: String, vararg pages: String): String {
        server.run("age write $name ${pages.joinToString(" ")}")
        return server.run("age list").substringAfter(name)
    }

    test("a material and a shape said together mint a formation") {
        val grown = grownBy("goldobelisks", "age", "gold_block", "obelisks")
        check("agesandtheart:obelisks" in grown) {
            "'gold_block obelisks' reached no formation at all:\n$grown"
        }
        check("of=minecraft:gold_block" in grown) {
            "the obelisks were not made of what the clause named:\n$grown"
        }
    }

    /**
     * **The one that proves an Age carrying formations actually generates**, which nothing else can: a
     * claim in a recipe is not a ring in the ground, and decoration failing is not something a command
     * reports. `applyBiomeDecoration` wraps whatever a feature throws in a `ReportedException` on a
     * worker thread — the chunk is abandoned, generation quietly stalls, and every `/age` command still
     * answers perfectly well. The server's own output is the only place it shows.
     *
     * This is the check that was missing when six formations went in: the offline ones all passed, the
     * recipe held what it should, and chunk generation was throwing on every chunk.
     *
     * **Two sizes rather than one**, because a size that belongs to its clause is a *second* rebuilding of
     * the feature — and a rebuilt feature is a new object where `applyBiomeDecoration` looks each one up
     * by identity (`decisions.md`). Two formations sized differently in one book is the shape that would
     * find that done outside the per-Age memo, which is the trap that has now bitten three times.
     */
    test("an Age full of formations generates without throwing") {
        val before = server.saidSoFar().length
        // **`pillars` rather than leaving the landform to the draw.** The failure this was written for only
        // appears over a landform of *ours* — `AgeBiomeSource` rather than the template's — and which
        // landform a sentence draws depends on the sentence, so a book that does not name one tests
        // whatever it happened to get. `pillars` chooses one outright.
        server.run(
            "age write formationworld 909 age pillared landmass " +
                "colossal gold_block rings minuscule blackstone obelisks",
        )

        // **A region rather than the spawn chunks.** The failure needs a biome the Age can produce but
        // that its biome source did not list, so it appears where the world varies — a handful of chunks
        // around spawn can miss it entirely, and did.
        server.run("execute in agesandtheart:formationworld run forceload add -7 -7 7 7")

        // The forceload returns once its chunks are generated and decorated, on worker threads, so whatever
        // that threw has been said by the time it does.
        fun saidSince() = server.saidSoFar().drop(before)
        fun thrown() = saidSince().lineSequence()
            .filter { "ReportedException" in it || "Biome decoration" in it }
            .toList()

        check(saidSince().isNotEmpty()) { "the server said nothing at all, so this is watching nothing" }
        check(thrown().isEmpty()) {
            "generating an Age with formations threw:\n  ${thrown().take(4).joinToString("\n  ")}"
        }
    }

    /**
     * **A pattern nobody described is still made of something.** The pool it draws from is a block tag,
     * which nothing binds until a server has loaded its packs — so a tag naming nothing is invisible
     * offline and almost invisible in play: generation says so once and carries on with the shape's own
     * stone, leaving an Age that merely looks dull.
     */
    test("a formation left unstated draws a substance") {
        val grown = grownBy("bareobelisks", "age", "obelisks")
        check("agesandtheart:obelisks" in grown) { "'obelisks' alone grew nothing:\n$grown" }
        check("of=#agesandtheart:formation_substance" in grown) {
            "'obelisks' alone should fall back to the pool it names:\n$grown"
        }

        val before = server.saidSoFar().length
        server.run("execute in agesandtheart:bareobelisks run forceload add -3 -3 3 3")
        fun saidSince() = server.saidSoFar().drop(before)
        // The forceload has returned, so its chunks are decorated and the pool drawn from; the assertion below
        // would pass on an empty log, so the log is checked for being one.
        check(saidSince().isNotEmpty()) { "the server said nothing at all, so this is watching nothing" }

        val complained = saidSince().lineSequence().filter { "made of nothing in particular" in it }.toList()
        check(complained.isEmpty()) {
            "the obelisks were made of nothing:\n  ${complained.take(2).joinToString("\n  ")}"
        }
    }

    /** Every shape is a page a writer can lay, which is the half a missing data file would lose in silence. */
    test("every shape the pack ships can be written") {
        val shapes = listOf("obelisks", "pyramids", "boulders", "spikes", "rings", "arches")
        val missed = shapes.filterNot { shape ->
            "agesandtheart:$shape" in grownBy("shape$shape", "age", "blackstone", shape)
        }
        check(missed.isEmpty()) { "these shapes are words the Art cannot grow: $missed" }
    }
})


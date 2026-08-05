package co.voik.agesandtheart.server

import co.voik.agesandtheart.worldgen.NEEDS_LANDFORMS
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * What only a generated world can answer: whether two recipes make the same ground.
 *
 * These were `scripts/checks/uncut-ground.txt` and the assertive half of `faults.txt`, where each bound
 * lived in a `#?` line above the command it judged. The bounds are unchanged; what changed is that
 * `differingBlocks` is now a number the command reported rather than the first integer on a line of prose —
 * which for the whole life of that layer was **the hour off the log's timestamp**.
 *
 * Every bound here is deliberately far from the reading it guards. Each says what regression it would
 * catch, and the two outcomes are orders of magnitude apart, so a loose bound loses nothing and survives
 * retuning.
 */
@Tags(NEEDS_SERVER, NEEDS_LANDFORMS)
class GenerationCheck : FunSpec({
    val server = DrivenServer.shared

    /**
     * **Uncut ground exists.** A carver that cuts nothing (`solid`) must hold territory of its own rather
     * than being swallowed by one that does — the regression this guards is the old behaviour, where the
     * two Ages differed by about *two* blocks because `solid` vanished under the plain union.
     *
     * Six orders of magnitude sit between the two readings.
     */
    test("a carver that cuts nothing keeps its own ground") {
        server.run("age compose riddledonly 7 terrain=hills sea=minecraft:water carvers=caves sky=plain")
        server.run("age compose riddledsolid 7 terrain=hills sea=minecraft:water carvers=caves,solid sky=plain")
        val differing = differingBlocks(server, "riddledonly", "riddledsolid", radius = 6)
        check(differing >= 10_000) {
            "'solid' is being swallowed again: only $differing block(s) differ, where uncut ground is ~174,000"
        }
    }

    /**
     * The control for it: two carvers that both *cut* must still union, so this pair differs by whatever
     * porosity adds rather than by territory.
     *
     * Bounded both ways because it is a control and both directions mean something. Nothing at all would
     * say porosity stopped adding; a reading up near the uncut-ground one (~174,000 over every chunk) would
     * say the two cutting carvers had stopped unioning and started dividing, which is the bug next door.
     */
    test("two carvers that both cut still union") {
        server.run("age compose riddledporous 7 terrain=hills sea=minecraft:water carvers=caves,porous sky=plain")
        val differing = differingBlocks(server, "riddledonly", "riddledporous", radius = 6)
        check(differing >= 1_000) { "porosity added nothing at all: $differing block(s) differ" }
        check(differing <= 150_000) {
            "two cutting carvers have started dividing territory rather than unioning: $differing block(s) differ"
        }
    }

    /**
     * **Step 9's acceptance criterion, as an assertion.** A one-territory Age has no seam, so a fault asked
     * for in one must build no node at all — `FaultCheck` proves that of the field tree offline, and this
     * is the same claim measured in a world.
     *
     * The ceiling is set where the two outcomes cannot be confused rather than at the measurement:
     * decoration noise at radius 2 runs to tens of blocks, where a whole-world throw of 32 moves every
     * column in all 25 chunks — hundreds of thousands. Nothing lands between.
     */
    test("a fault asked for in an undivided Age builds nothing") {
        server.run("age compose lonelevel 4242 terrain=hills sea=water")
        for (form in listOf("scarp", "rift")) {
            server.run("age compose lone$form 4242 terrain=hills sea=water terrain.seam=$form")
            val differing = differingBlocks(server, "lonelevel", "lone$form", radius = 2)
            check(differing <= 2_000) {
                "a $form was built into an Age with no seam to build it on: $differing block(s) differ"
            }
        }
    }

    /**
     * A rift *does* show where there is a seam to cut — the other half of the claim above, and the one
     * that would catch a fault node that had quietly stopped being reached.
     *
     * **Radius 8, and against its own control.** A territory is about 400 blocks and radius 2 samples 80,
     * so a working rift measured 51 blocks at radius 2 — indistinguishable from noise. The twin is an
     * identical recipe under another name, which is what makes the number readable.
     */
    test("a fault shows where there is a seam") {
        server.run("age compose seamsheared 4242 terrain=hills,hills sea=water terrain.seam=sheared")
        server.run("age compose seamtwin 4242 terrain=hills,hills sea=water terrain.seam=sheared")
        server.run("age compose seamrift 4242 terrain=hills,hills sea=water terrain.seam=rift")

        val control = differingBlocks(server, "seamsheared", "seamtwin", radius = 8)
        val riven = differingBlocks(server, "seamsheared", "seamrift", radius = 8)
        check(riven > control * 4) {
            "a rift between two territories is not distinguishable from a same-recipe control: " +
                "$riven against $control"
        }
    }
    /**
     * **Ores reach whatever the Age is made of**, which they did not until 2026-08-04 and said nothing
     * about it.
     *
     * An ore feature replaces what its `RuleTest` matches, and vanilla's two match vanilla's stone —
     * `stone_ore_replaceables` is stone, granite, diorite and andesite, and `deepslate_ore_replaceables`
     * is deepslate and tuff. An Age of blackstone therefore grew **no ore whatsoever**, and the pair below
     * came back `identical`: eight times the diamonds, in a world that could hold none of them.
     *
     * This is §3.3's silent drop in the place a writer would least look for it, so the check is the
     * outcome rather than the mechanism — ask for ore in a world of the wrong rock and see ground move.
     */
    test("ores reach an Age made of something other than stone") {
        val blackstone = "terrain=hills[stone=minecraft:blackstone] sea=minecraft:water carvers=solid sky=plain"
        server.run("age compose blackbare 4242 $blackstone")
        server.run("age compose blackrich 4242 $blackstone features.places=minecraft:ore_diamond[amount=8]")
        val differing = differingBlocks(server, "blackbare", "blackrich", radius = 2)
        check(differing > 0) {
            "an Age of blackstone grew no ore at all: the two agree block for block"
        }
    }
})

/** How far apart two Ages generate, in blocks — the number `/age compare` reports rather than says. */
private fun differingBlocks(server: DrivenServer, first: String, second: String, radius: Int): Int =
    server.ask("compare", "$first $second $radius").get("differingBlocks").asInt

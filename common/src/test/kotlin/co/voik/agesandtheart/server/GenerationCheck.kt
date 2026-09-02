package co.voik.agesandtheart.server

import co.voik.agesandtheart.worldgen.NEEDS_LANDFORMS
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * What only a generated world can answer: whether two recipes make the same ground.
 *
 * Every bound here is deliberately far from the reading it guards. Each says what regression it would
 * catch, and the two outcomes are orders of magnitude apart, so a loose bound loses nothing and survives
 * retuning.
 *
 * **What is deliberately not here is the seams** (design §3.4). A fault moves a swathe of landscape, two
 * Ages of one recipe do not generate identically, and there is no defensible line between the two: the
 * bound that judged it failed on a different form with a different count on each run and passed whenever
 * the spec ran alone. `FaultCheck` holds the field tree to it offline, where the reading is exact, and
 * whether a seam *looks* like anything is a thing to walk to.
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
        server.run("age compose riddledonly 7 landmass=hills sea=minecraft:water rock=caves sky=plain")
        server.run("age compose riddledsolid 7 landmass=hills sea=minecraft:water rock=caves,solid sky=plain")
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
        server.run("age compose riddledporous 7 landmass=hills sea=minecraft:water rock=caves,porous sky=plain")
        val differing = differingBlocks(server, "riddledonly", "riddledporous", radius = 6)
        check(differing >= 1_000) { "porosity added nothing at all: $differing block(s) differ" }
        check(differing <= 150_000) {
            "two cutting carvers have started dividing territory rather than unioning: $differing block(s) differ"
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
        val blackstone = "landmass=hills[stone=minecraft:blackstone] sea=minecraft:water rock=solid sky=plain"
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

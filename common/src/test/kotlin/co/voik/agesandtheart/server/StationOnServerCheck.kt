package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * That a grinder and a pulper work only with what they need beside them, and what they make of it.
 *
 * On a server because the recipes are datapack JSON and the stations' neighbours are real blocks. Each
 * station is built high over the forceloaded spawn chunk, loaded through `item replace` as a player's
 * right-click would, and the clock sprinted past one run.
 */
@Tags(NEEDS_SERVER)
class StationOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    fun at(x: Int) = "$x $STATION_Y 0"

    fun build(x: Int, station: String, vararg neighbours: Pair<Int, String>) {
        server.run("forceload add 0 0")
        server.run("setblock ${at(x)} agesandtheart:$station")
        for ((offset, block) in neighbours) server.run("setblock $x $STATION_Y $offset $block")
    }

    fun load(x: Int, item: String, count: Int = 1) {
        server.run("item replace block ${at(x)} container.${INPUT_SLOT} with $item $count")
    }

    fun runPastOneRun() {
        server.run("tick sprint $SPRINTED_TICKS")
        Thread.sleep(SPRINT_WAIT_MILLIS)
    }

    fun contents(x: Int): String = server.run("data get block ${at(x)} Items")

    fun isInState(x: Int, station: String, activity: String): Boolean =
        server.run("execute if block ${at(x)} agesandtheart:$station[activity=$activity]").startsWith("Test passed")

    test("a grinder with arc crystal and a grindstone beside it grinds a deretheni stone to nine dust") {
        build(GRINDER_X, "grinder", 1 to "agesandtheart:arc_crystal_block", -1 to "minecraft:grindstone")
        load(GRINDER_X, "agesandtheart:pitchstone")
        runPastOneRun()
        val held = contents(GRINDER_X)
        check("agesandtheart:pitchstone_dust" in held && "count: 9" in held) { "the grinder holds: $held" }
    }

    test("a grinder without its grindstone stalls, and says so in its block state") {
        build(STALLED_GRINDER_X, "grinder", 1 to "agesandtheart:arc_crystal_block")
        load(STALLED_GRINDER_X, "agesandtheart:scorched_temperstone")
        runPastOneRun()
        val held = contents(STALLED_GRINDER_X)
        check("minecraft:gunpowder" !in held) { "a grinder with no grindstone made gunpowder: $held" }
        check(isInState(STALLED_GRINDER_X, "grinder", "stalled")) { "the grinder is not showing as stalled" }
    }

    test("a pulper pulps a stripped log and drains a level of its cauldron") {
        build(
            PULPER_X,
            "pulper",
            1 to "agesandtheart:arc_crystal_block",
            -1 to "minecraft:water_cauldron[level=3]",
        )
        load(PULPER_X, "minecraft:stripped_oak_log")
        runPastOneRun()
        val held = contents(PULPER_X)
        check("agesandtheart:pulp" in held && "count: 2" in held) { "the pulper holds: $held" }
        val drained = server.run("execute if block $PULPER_X $STATION_Y -1 minecraft:water_cauldron[level=2]")
        check(drained.startsWith("Test passed")) { "the cauldron was not drained by one level: $drained" }
    }

    test("a grinder grinds coal and charcoal alike to one soot each") {
        for ((x, fuel) in listOf(COAL_GRINDER_X to "minecraft:coal", CHARCOAL_GRINDER_X to "minecraft:charcoal")) {
            build(x, "grinder", 1 to "agesandtheart:arc_crystal_block", -1 to "minecraft:grindstone")
            load(x, fuel)
        }
        runPastOneRun()
        for (x in listOf(COAL_GRINDER_X, CHARCOAL_GRINDER_X)) {
            val held = contents(x)
            check("agesandtheart:soot" in held && "count: 1" in held) { "the grinder at $x holds: $held" }
        }
    }

    test("a hopper feeds a station only what it can work, and a hopper under it takes the result") {
        build(HOPPERED_X, "grinder", 1 to "agesandtheart:arc_crystal_block", -1 to "minecraft:grindstone")
        val above = "$HOPPERED_X ${STATION_Y + 1} 0"
        val below = "$HOPPERED_X ${STATION_Y - 1} 0"
        server.run("setblock $above minecraft:hopper[facing=down]")
        server.run("setblock $below minecraft:hopper[facing=down]")
        server.run("item replace block $above container.0 with minecraft:dirt")
        server.run("item replace block $above container.1 with agesandtheart:pitchstone")
        runPastOneRun()
        val leftAbove = server.run("data get block $above Items")
        val collectedBelow = server.run("data get block $below Items")
        check("minecraft:dirt" in leftAbove) { "the dirt left the hopper, so the grinder took it: $leftAbove" }
        check("agesandtheart:pitchstone_dust" in collectedBelow) { "nothing reached the lower hopper: $collectedBelow" }
    }
}) {
    private companion object {
        const val STATION_Y = 200
        const val GRINDER_X = 2
        const val STALLED_GRINDER_X = 5
        const val PULPER_X = 8
        const val HOPPERED_X = 11
        const val COAL_GRINDER_X = 14
        const val CHARCOAL_GRINDER_X = 17
        const val INPUT_SLOT = 0

        /** Past the pulper's 300-tick run, with room to spare. */
        const val SPRINTED_TICKS = 400
        const val SPRINT_WAIT_MILLIS = 3_000L
    }
}

package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * That the drying rack finishes a masterwork grade only under the sky its recipe names, one item at a time
 * into its output: an ink cake under a black sun, a wet sheet in an Age with an open lava sea, and neither
 * anywhere else. A recipe naming no sky, rotten flesh to leather, dries in the overworld.
 *
 * On a server because the recipes are datapack JSON and the skies are written Ages. Each rack has a roof,
 * so an Age's rain cannot start it again partway.
 */
@Tags(NEEDS_SERVER, NEEDS_TIME)
class DryingOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    fun inAge(name: String, command: String) = server.run("execute in agesandtheart:$name run $command")

    fun rackIn(age: String?, x: Int, item: String) {
        val run: (String) -> String = { command -> if (age == null) server.run(command) else inAge(age, command) }
        run("forceload add $x 0")
        run("setblock $x $RACK_Y 0 agesandtheart:drying_rack")
        run("setblock $x ${RACK_Y + 1} 0 minecraft:stone")
        run("item replace block $x $RACK_Y 0 container.0 with $item 2")
    }

    fun holds(age: String?, x: Int): String {
        val command = "data get block $x $RACK_Y 0 Items"
        return if (age == null) server.run(command) else inAge(age, command)
    }

    test("the right sky finishes each grade into the output, and the wrong one nothing") {
        server.run("age write $BLACK_SUN_AGE 7 age black sun")
        server.run("age write $LAVA_SEA_AGE 7 age lava sea")
        rackIn(BLACK_SUN_AGE, CAKE_X, "agesandtheart:ink_cake")
        rackIn(BLACK_SUN_AGE, SHEET_X, "agesandtheart:wet_paper_sheet")
        rackIn(LAVA_SEA_AGE, SHEET_X, "agesandtheart:wet_paper_sheet")
        rackIn(LAVA_SEA_AGE, CAKE_X, "agesandtheart:ink_cake")
        rackIn(null, CAKE_X, "agesandtheart:ink_cake")
        rackIn(null, FLESH_X, "minecraft:rotten_flesh")
        server.run("tick sprint $BOTH_DRIED_AND_A_LITTLE")
        Thread.sleep(SPRINT_WAIT_MILLIS)

        val curedUnderTheBlackSun = holds(BLACK_SUN_AGE, CAKE_X)
        val bothInTheOutput = "{count: 2, Slot: ${OUTPUT}b, id: \"agesandtheart:cured_ink_cake\"}" in curedUnderTheBlackSun
        check(bothInTheOutput && "ink_cake\"" !in curedUnderTheBlackSun.replace("cured_ink_cake\"", "")) {
            "the black sun's rack should hold both cakes cured in its output: $curedUnderTheBlackSun"
        }
        val driedOverLava = holds(LAVA_SEA_AGE, SHEET_X)
        check("agesandtheart:masterwork_paper" in driedOverLava) { "the lava sea's rack holds: $driedOverLava" }
        val sheetUnderTheBlackSun = holds(BLACK_SUN_AGE, SHEET_X)
        check("agesandtheart:wet_paper_sheet" in sheetUnderTheBlackSun) { "a sheet dried under a black sun: $sheetUnderTheBlackSun" }
        val cakeOverLava = holds(LAVA_SEA_AGE, CAKE_X)
        check("agesandtheart:ink_cake" in cakeOverLava) { "a cake cured in a lava-sea Age: $cakeOverLava" }
        val cakeAtHome = holds(null, CAKE_X)
        check("agesandtheart:ink_cake" in cakeAtHome) { "a cake cured in the overworld: $cakeAtHome" }
        val fleshAtHome = holds(null, FLESH_X)
        check("{count: 2, Slot: ${OUTPUT}b, id: \"minecraft:leather\"}" in fleshAtHome) {
            "rotten flesh, which names no sky, should dry into leather in the overworld: $fleshAtHome"
        }
    }
}) {
    private companion object {
        const val BLACK_SUN_AGE = "dryingblacksun"
        const val LAVA_SEA_AGE = "dryinglavasea"
        const val RACK_Y = 250
        const val CAKE_X = 0
        const val SHEET_X = 4
        const val FLESH_X = 8

        const val OUTPUT = 6

        /** Two items at the rack's 400 ticks apiece, and a margin over. */
        const val BOTH_DRIED_AND_A_LITTLE = 1_000

        /** A sprint runs off the command's thread. */
        const val SPRINT_WAIT_MILLIS = 5_000L
    }
}

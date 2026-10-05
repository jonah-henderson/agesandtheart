package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * That the drying rack finishes a masterwork grade only under the sky its recipe names, each input drying
 * alongside the others into its output: an ink cake under a black sun, a wet sheet in an Age with an open lava sea, and neither
 * anywhere else. A recipe naming no sky, rotten flesh to leather, dries in the overworld.
 *
 * On a server because the recipes are datapack JSON and the skies are written Ages. Each rack has a roof,
 * so an Age's rain cannot start it again partway.
 */
@Tags(NEEDS_SERVER, NEEDS_TIME)
class DryingOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    fun dimensionOf(age: String?): String = if (age == null) "minecraft:overworld" else "agesandtheart:$age"

    fun inLevel(age: String?, command: String) = server.run("execute in ${dimensionOf(age)} run $command")

    /** Forceloads the chunk and waits for it: a just-written Age is still making its ground. */
    fun loaded(age: String?, x: Int) {
        inLevel(age, "forceload add $x 0")
        val isThere = (1..LOAD_TRIES).any {
            Thread.sleep(LOAD_WAIT_MILLIS)
            server.run("execute in ${dimensionOf(age)} if loaded $x 0 0").startsWith("Test passed")
        }
        check(isThere) { "the chunk at ($x, 0) in ${dimensionOf(age)} never loaded" }
    }

    fun rackIn(age: String?, x: Int, item: String, inputs: Int = 1) {
        loaded(age, x)
        inLevel(age, "setblock $x $RACK_Y 0 agesandtheart:drying_rack")
        inLevel(age, "setblock $x ${RACK_Y + 1} 0 minecraft:stone")
        repeat(inputs) { slot -> inLevel(age, "item replace block $x $RACK_Y 0 container.$slot with $item 2") }
    }

    /** Waits until [count] of [made] are in a rack's output, on a sprint that ends when it ends. */
    fun untilBothDried(age: String?, x: Int, made: String, count: Int = 2): String = server.untilPasses(
        "execute in ${dimensionOf(age)} if items block $x $RACK_Y 0 container.$OUTPUT $made[count=$count]",
        tries = DRIED_TRIES,
        pauseMillis = DRIED_WAIT_MILLIS,
    )

    fun holds(age: String?, x: Int): String = inLevel(age, "data get block $x $RACK_Y 0 Items")

    test("the right sky finishes each grade into the output, and the wrong one nothing") {
        server.run("age write $BLACK_SUN_AGE 7 age black sun")
        server.run("age write $LAVA_SEA_AGE 7 age lava sea")
        rackIn(BLACK_SUN_AGE, CAKE_X, "agesandtheart:ink_cake")
        rackIn(BLACK_SUN_AGE, SHEET_X, "agesandtheart:wet_paper_sheet")
        rackIn(LAVA_SEA_AGE, SHEET_X, "agesandtheart:wet_paper_sheet")
        rackIn(LAVA_SEA_AGE, CAKE_X, "agesandtheart:ink_cake")
        rackIn(null, CAKE_X, "agesandtheart:ink_cake")
        rackIn(null, FLESH_X, "minecraft:rotten_flesh", inputs = FLESH_INPUTS)
        server.run("tick sprint $BOTH_DRIED_AND_A_LITTLE")

        val cured = untilBothDried(BLACK_SUN_AGE, CAKE_X, "agesandtheart:cured_ink_cake")
        check(cured.startsWith("Test passed")) { "the black sun's rack holds: ${holds(BLACK_SUN_AGE, CAKE_X)}" }
        val driedOverLava = untilBothDried(LAVA_SEA_AGE, SHEET_X, "agesandtheart:masterwork_paper")
        check(driedOverLava.startsWith("Test passed")) { "the lava sea's rack holds: ${holds(LAVA_SEA_AGE, SHEET_X)}" }
        // Three inputs of two take 800 ticks together and 2,400 in turn, past the sprint and the wait after it.
        val leather = untilBothDried(null, FLESH_X, "minecraft:leather", count = 2 * FLESH_INPUTS)
        check(leather.startsWith("Test passed")) {
            "rotten flesh, which names no sky, should dry into leather in the overworld, every input at once: ${holds(null, FLESH_X)}"
        }

        // Read only once the rest have dried, so each of these has had as long as they took.
        val sheetUnderTheBlackSun = holds(BLACK_SUN_AGE, SHEET_X)
        check("agesandtheart:wet_paper_sheet" in sheetUnderTheBlackSun) { "a sheet dried under a black sun: $sheetUnderTheBlackSun" }
        val cakeOverLava = holds(LAVA_SEA_AGE, CAKE_X)
        check("agesandtheart:ink_cake" in cakeOverLava) { "a cake cured in a lava-sea Age: $cakeOverLava" }
        val cakeAtHome = holds(null, CAKE_X)
        check("agesandtheart:ink_cake" in cakeAtHome) { "a cake cured in the overworld: $cakeAtHome" }
    }
}) {
    private companion object {
        const val BLACK_SUN_AGE = "dryingblacksun"
        const val LAVA_SEA_AGE = "dryinglavasea"
        const val RACK_Y = 250
        const val CAKE_X = 0
        const val SHEET_X = 4
        const val FLESH_X = 8
        const val FLESH_INPUTS = 3

        const val OUTPUT = 6

        /** Two items at the rack's 400 ticks apiece, and a margin over. */
        const val BOTH_DRIED_AND_A_LITTLE = 1_000

        const val LOAD_TRIES = 20
        const val LOAD_WAIT_MILLIS = 500L

        /**
         * A sprint runs off the command's thread, and slowly while two new Ages are still making their ground.
         * Thirty seconds, which is also six hundred ordinary ticks should the sprint end first.
         */
        const val DRIED_TRIES = 60
        const val DRIED_WAIT_MILLIS = 500L
    }
}

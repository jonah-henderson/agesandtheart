package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * That a deretheni stone mined whole drops itself, and a shaved one the plates it has left.
 *
 * On a server because the loot table is datapack JSON, which 26.3 reads without complaint when a key is
 * misspelled. `loot insert … mine` breaks the block as a pickaxe would, into a chest beside it.
 */
@Tags(NEEDS_SERVER)
class PitchstoneOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    fun mined(shaved: Int): String {
        val x = FIRST_X + shaved * SPACING
        server.run("forceload add 0 0")
        server.run("setblock $x $CHECK_Y 0 agesandtheart:pitchstone[shaved=$shaved]")
        server.run("setblock $x $CHECK_Y 1 minecraft:chest")
        server.run("loot insert $x $CHECK_Y 1 mine $x $CHECK_Y 0 minecraft:diamond_pickaxe")
        return server.run("data get block $x $CHECK_Y 1 Items")
    }

    test("a whole stone drops itself") {
        val held = mined(shaved = 0)
        check("\"agesandtheart:pitchstone\"" in held && "count: 1" in held) { "the chest holds: $held" }
        check("pitchstone_plate" !in held) { "a whole stone dropped plates: $held" }
    }

    for ((shaved, platesLeft) in listOf(1 to 3, 2 to 2, 3 to 1)) {
        test("a stone shaved $shaved times drops $platesLeft plates") {
            val held = mined(shaved)
            check("agesandtheart:pitchstone_plate" in held && "count: $platesLeft" in held) { "the chest holds: $held" }
        }
    }
}) {
    private companion object {
        const val CHECK_Y = 200
        const val FIRST_X = 2
        const val SPACING = 3
    }
}

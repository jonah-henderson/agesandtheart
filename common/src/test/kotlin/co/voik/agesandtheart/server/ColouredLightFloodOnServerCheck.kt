package co.voik.agesandtheart.server

import com.google.gson.JsonParser
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * Coloured light floods exactly as vanilla's block light does: a jack o'lantern in a room of walls, a doorway, a
 * window, slabs and a floor of them, leaves and water, compared block for block against the server's own light engine.
 */
@Tags(NEEDS_SERVER)
class ColouredLightFloodOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    fun place(command: String) {
        val said = server.run(command)
        check("Successfully" in said || "Changed the block" in said || "filled" in said) { "$command: $said" }
    }

    test("a light floods every block it reaches at the level vanilla's light engine holds there") {
        server.run("forceload add $X $Z ${X + SIDE} ${Z + SIDE}")
        place("fill $X $Y $Z ${X + SIDE} ${Y + HEIGHT} ${Z + SIDE} minecraft:stone hollow")
        // A wall across the room with a doorway in it, and a room sealed off beyond the far wall.
        place("fill ${X + 6} ${Y + 1} ${Z + 1} ${X + 6} ${Y + HEIGHT - 1} ${Z + SIDE - 1} minecraft:stone")
        place("fill ${X + 6} ${Y + 1} ${Z + 4} ${X + 6} ${Y + 2} ${Z + 4} minecraft:air")
        place("fill ${X + SIDE} ${Y} ${Z} ${X + SIDE + 4} ${Y + HEIGHT} ${Z + 4} minecraft:stone hollow")
        // A window, and what dims or bends light in the lit room.
        place("setblock $X ${Y + 2} ${Z + 3} minecraft:glass")
        place("setblock ${X + 3} ${Y + 1} ${Z + 6} minecraft:stone_slab")
        place("setblock ${X + 4} ${Y + 1} ${Z + 2} minecraft:oak_leaves[persistent=true]")
        place("setblock ${X + 2} ${Y + 1} ${Z + 7} minecraft:water")
        place("setblock ${X + 4} ${Y + 3} ${Z + 4} minecraft:oak_fence")
        // A floor of bottom slabs over the lit room, whose full undersides light from below cannot cross.
        place("fill ${X + 1} ${Y + 4} ${Z + 1} ${X + 5} ${Y + 4} ${Z + SIDE - 1} minecraft:stone_slab")
        place("setblock ${X + 2} ${Y + 2} ${Z + 3} minecraft:jack_o_lantern")

        var last = ""
        server.waitUntil("the light engine settling") {
            last = server.run("age lightflood json ${X + 2} ${Y + 2} ${Z + 3}")
            JsonParser.parseString(last).asJsonObject.get("differing")?.asInt == 0
        }
        val compared = JsonParser.parseString(last).asJsonObject.get("compared").asInt
        check(compared > ENOUGH_LIT) { "only $compared lit blocks were compared: $last" }
    }
})

private const val X = 6000
private const val Y = 240
private const val Z = 6000
private const val SIDE = 10
private const val HEIGHT = 6

/** Far fewer than a light of fifteen reaches in a room this size, so the comparison had something to compare. */
private const val ENOUGH_LIT = 100

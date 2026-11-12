package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * That water never flows into held air, where it flows into ordinary air beside it.
 * On a server because whether a fluid may spread is decided by a block tag, and only a server holds the tags.
 */
@Tags(NEEDS_SERVER)
class HeldAirOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    fun inWorld(command: String) = server.run("execute in minecraft:overworld run $command")

    fun holds(x: Int, block: String): Boolean =
        inWorld("execute if block $x $ROW_Y 0 $block").startsWith("Test passed")

    test("water flows into the air on one side and not into the held air on the other") {
        inWorld("forceload add $HELD_X 0")
        server.waitUntil("the row's chunk loading") { server.run("execute in minecraft:overworld if loaded $HELD_X 0 0").startsWith("Test passed") }
        // A floor wide enough that no drop lies within the water's reach, or it flows only toward the nearest edge.
        inWorld("fill ${SOURCE_X - FLOOR_REACH} ${ROW_Y - 1} ${-FLOOR_REACH} ${SOURCE_X + FLOOR_REACH} ${ROW_Y - 1} $FLOOR_REACH minecraft:stone")
        inWorld("setblock $HELD_X $ROW_Y 0 agesandtheart:held_air")
        inWorld("setblock $OPEN_X $ROW_Y 0 minecraft:air")
        inWorld("setblock $SOURCE_X $ROW_Y 0 minecraft:water")
        server.sprint(SPREAD_TICKS)
        val spread = server.untilPasses(
            "execute in minecraft:overworld if block $OPEN_X $ROW_Y 0 minecraft:water",
            tries = SPREAD_TRIES,
            pauseMillis = SPREAD_WAIT_MILLIS,
        ).startsWith("Test passed")
        fun row() = (HELD_X..OPEN_X).joinToString { x -> "$x: " + inWorld("execute if block $x $ROW_Y 0 minecraft:water") }
        check(spread) { "the water did not spread into ordinary air, so this proved nothing: ${row()}" }
        check(holds(HELD_X, "agesandtheart:held_air")) { "the water flowed into the held air" }
    }
})

private const val ROW_Y = 200
private const val HELD_X = 4
private const val SOURCE_X = 5
private const val OPEN_X = 6
private const val SPREAD_TICKS = 40

/** Past the four blocks a source looks for somewhere to fall. */
private const val FLOOR_REACH = 6
private const val SPREAD_TRIES = 20
private const val SPREAD_WAIT_MILLIS = 250L

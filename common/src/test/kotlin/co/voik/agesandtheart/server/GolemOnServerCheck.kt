package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** The astrite golem's laser (`content/AstriteGolem`): fired at what it fights from range, and refilled by light. */
@Tags(NEEDS_SERVER)
class GolemOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    fun inWorld(command: String) = server.run("execute in minecraft:overworld run $command")

    fun passes(test: String) = inWorld("execute $test").startsWith("Test passed")

    fun field(x: Int) {
        inWorld("forceload add ${x - 4} ${Z - 4} ${x + 16} ${Z + 4}")
        server.waitUntil("the field's chunks loading") { passes("if loaded $x $Y $Z") }
        inWorld("fill ${x - 4} ${Y - 1} ${Z - 4} ${x + 16} ${Y - 1} ${Z + 4} minecraft:stone")
        inWorld("weather clear")
    }

    fun golemHasCharge(tag: String, charge: Int) = passes("if entity @e[tag=$tag,nbt={laser_charge:$charge}]")

    test("a golem fires at a zombie out of its reach until it falls, spending charge") {
        val x = FIRING_X
        field(x)
        inWorld("time set midnight")
        inWorld("summon agesandtheart:astrite_golem $x $Y $Z {Tags:[\"firing_golem\"]}")
        inWorld("summon minecraft:zombie ${x + 10} $Y $Z {NoAI:1b,PersistenceRequired:1b,Tags:[\"laser_target\"]}")
        server.sprint(LONG_ENOUGH_TO_FELL_A_ZOMBIE)
        check(!passes("if entity @e[tag=laser_target]")) { "a zombie ten blocks from a golem was still standing" }
        check(!golemHasCharge("firing_golem", FULL_CHARGE)) { "the golem felled the zombie without spending any charge" }
        inWorld("kill @e[tag=firing_golem]")
    }

    test("a drained golem refills in sunlight, and not in the dark") {
        val x = REFILLING_X
        field(x)
        inWorld("time set midnight")
        inWorld("summon agesandtheart:astrite_golem $x $Y $Z {laser_charge:0,NoAI:1b,Tags:[\"drained_golem\"]}")
        server.sprint(REFILLING_TICKS)
        check(golemHasCharge("drained_golem", 0)) { "a golem refilled at midnight with no light about it" }
        inWorld("time set noon")
        server.sprint(REFILLING_TICKS)
        check(!golemHasCharge("drained_golem", 0)) { "a golem standing in the noon sun did not refill" }
        inWorld("kill @e[tag=drained_golem]")
    }
})

private const val Y = 220
private const val Z = -9200
private const val FIRING_X = -11000
private const val REFILLING_X = -11100
private const val FULL_CHARGE = 32

/** Four shots at a shot a second, with time to find the zombie and turn to it. */
private const val LONG_ENOUGH_TO_FELL_A_ZOMBIE = 160

/** Two shots' worth of noon sun, with the light read once a second. */
private const val REFILLING_TICKS = 100

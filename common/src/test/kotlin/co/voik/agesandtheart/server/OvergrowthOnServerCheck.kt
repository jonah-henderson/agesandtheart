package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * What an overgrowth and its thorns do that does not depend on a random roll (design §5.2.2): a cut thorn
 * chain falls away, what stands on a root survives and what has no ground does not, a crop on one ripens
 * stage by stage, and digging one yields nothing.
 *
 * **Only what a scheduled tick or a placement settles.** Spreading, raising and growing are decided by
 * vanilla's random ticks and by chance, and a check of them can only wait and hope; their rates are
 * arithmetic `OvergrowthCheck` holds, and the rest is for a walk.
 */
@Tags(NEEDS_SERVER)
class OvergrowthOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    fun inWorld(command: String) = server.run("execute in minecraft:overworld run $command")
    fun isAt(x: Int, y: Int, z: Int, block: String) = inWorld("execute if block $x $y $z $block").startsWith("Test passed")

    /** Runs [ticks] ticks and waits for them: a sprint is answered at once and carries on over the following ticks. */
    fun sprint(ticks: Int) {
        server.run("tick sprint $ticks")
        var waited = 0
        while (waited < SPRINT_TRIES && server.run("tick query").contains("is sprinting")) {
            Thread.sleep(SPRINT_POLL_MILLIS)
            waited++
        }
        check(waited < SPRINT_TRIES) { "a sprint of $ticks ticks never finished" }
    }

    val loaded by lazy {
        inWorld("forceload add 0 0 31 31")
        val loaded = (1..LOAD_TRIES).any {
            Thread.sleep(LOAD_WAIT_MILLIS)
            inWorld("execute if loaded 31 $PLATFORM_Y 31").startsWith("Test passed")
        }
        check(loaded) { "the platform's chunks never loaded" }
    }

    /** A bare stone floor with nothing on it, so one test's growth cannot reach another's. */
    fun freshGround(): Int {
        loaded
        inWorld("fill 0 ${PLATFORM_Y + 1} 0 31 ${PLATFORM_Y + CLEARED_HEIGHT} 31 minecraft:air")
        inWorld("fill 0 $PLATFORM_Y 0 31 $PLATFORM_Y 31 minecraft:stone")
        inWorld("kill @e[type=minecraft:item]")
        return PLATFORM_Y + 1
    }

    test("a thorn chain with its root cut away falls away, and the part still attached stays") {
        val y = freshGround()
        inWorld("setblock 12 $y 28 $OVERGROWTH")
        inWorld("setblock 12 ${y + 1} 28 $THORNS[anchor=down,heading=up,reach=1]")
        inWorld("setblock 12 ${y + 2} 28 $THORNS[anchor=down,heading=up,reach=2]")
        sprint(SETTLE_TICKS)
        check(isAt(12, y + 2, 28, THORNS)) { "an anchored chain fell away of its own accord" }
        inWorld("setblock 12 $y 28 minecraft:air")
        sprint(SETTLE_TICKS)
        check(!isAt(12, y + 1, 28, THORNS)) { "the segment on the cut root stayed" }
        check(!isAt(12, y + 2, 28, THORNS)) { "the segment past the cut stayed" }
    }

    test("a plant stands on an overgrowth, and falls where it has no ground") {
        val y = freshGround()
        inWorld("setblock 28 $y 28 $OVERGROWTH")
        inWorld("setblock 28 ${y + 1} 28 minecraft:wheat[age=0]")
        inWorld("setblock 28 ${y + 1} 31 minecraft:wheat[age=0]")
        // A plant is only asked whether it can stand when something beside it changes.
        inWorld("setblock 29 ${y + 1} 28 minecraft:stone")
        inWorld("setblock 29 ${y + 1} 31 minecraft:stone")
        sprint(SETTLE_TICKS)
        check(isAt(28, y + 1, 28, "minecraft:wheat")) { "wheat could not stand on an overgrowth" }
        check(!isAt(28, y + 1, 31, "minecraft:wheat")) { "wheat stood on bare stone, so this check proves nothing" }
    }

    test("a crop on an overgrowth ripens in about twenty seconds") {
        val y = freshGround()
        inWorld("setblock 20 $y 20 $OVERGROWTH")
        inWorld("setblock 20 ${y + 1} 20 minecraft:wheat[age=0]")
        // A fire holds the spreading back, so what is under test is the ripening alone.
        inWorld("setblock 22 ${y + 1} 20 minecraft:campfire[lit=true]")
        sprint(RIPEN_TICKS)
        check(isAt(20, y + 1, 20, "minecraft:wheat[age=7]")) { "the wheat had not ripened" }
    }

    test("an overgrowth drops nothing, with silk touch or without") {
        val y = freshGround()
        inWorld("setblock 24 $y 24 $OVERGROWTH")
        for (tool in listOf("minecraft:diamond_shovel", "minecraft:diamond_shovel[enchantments={\"minecraft:silk_touch\":1}]")) {
            val said = inWorld("loot spawn 24 ${y + 3} 24 mine 24 $y 24 $tool")
            check(!said.contains("Dropped")) { "an overgrowth mined with $tool dropped something: $said" }
        }
    }
}) {
    private companion object {
        const val LOAD_TRIES = 20
        const val LOAD_WAIT_MILLIS = 500L
        const val SPRINT_POLL_MILLIS = 250L
        const val SPRINT_TRIES = 960
        const val PLATFORM_Y = 200
        const val OVERGROWTH = "agesandtheart:overgrowth"
        const val THORNS = "agesandtheart:thorn_vine"
        const val SETTLE_TICKS = 40
        const val RIPEN_TICKS = 460
        const val CLEARED_HEIGHT = 16
    }
}

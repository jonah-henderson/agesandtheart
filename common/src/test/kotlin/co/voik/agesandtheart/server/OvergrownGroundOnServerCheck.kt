package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * That an Age whose instability bought overgrown ground is generated with patches of overgrowth in it, and the
 * same Age without it is not. The control is what makes an empty count mean anything.
 *
 * The step is forced before any chunk exists, since only ground generated afterwards takes it.
 */
@Tags(NEEDS_SERVER, NEEDS_TIME)
class OvergrownGroundOnServerCheck : FunSpec({

    val server = DrivenServer.shared

    fun inThe(age: String, command: String) = server.run("execute in agesandtheart:$age run $command")

    fun isLoaded(age: String): Boolean = listOf(FAR, FAR + REACH).all { x ->
        listOf(FAR, FAR + REACH).all { z ->
            inThe(age, "execute if loaded $x $PROBE_HEIGHT $z run time query gametime").any(Char::isDigit)
        }
    }

    /** How much overgrowth [age] holds in the first stretch of what was loaded, counted by removing it. */
    fun overgrowthIn(age: String): Int {
        var total = 0
        for (x in FAR..<FAR + SCANNED step TILE) {
            for (z in FAR..<FAR + SCANNED step TILE) {
                for (y in SCAN_FROM..<SCAN_TO step TILE) {
                    val said = inThe(age, "fill $x $y $z ${x + TILE - 1} ${y + TILE - 1} ${z + TILE - 1} minecraft:air replace agesandtheart:overgrowth")
                    total += CHANGED.find(said)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                }
            }
        }
        return total
    }

    test("overgrown ground lays patches of overgrowth into new ground, and nothing else does") {
        for (name in listOf(OVERGROWN, PLAIN)) {
            val said = server.run("age write $name $SEED $SENTENCE")
            check(said.contains("Created Age")) { "'$SENTENCE' was not written: $said" }
        }
        val forced = server.run("age instability $OVERGROWN force overgrown_ground $MOST_STEPS")
        check(forced.contains("overgrown_ground")) { "overgrown ground could not be forced: $forced" }
        for (name in listOf(OVERGROWN, PLAIN)) {
            inThe(name, "forceload add $FAR $FAR ${FAR + REACH} ${FAR + REACH}")
        }
        server.waitUntil("the forced chunks loading") { listOf(OVERGROWN, PLAIN).all { isLoaded(it) } }

        val patched = overgrowthIn(OVERGROWN)
        val control = overgrowthIn(PLAIN)
        for (name in listOf(OVERGROWN, PLAIN)) inThe(name, "forceload remove all")
        check(control == 0) { "the control Age held $control overgrowth blocks, so this proves nothing" }
        check(patched >= ENOUGH_FOR_A_PATCH) { "the overgrown Age held only $patched overgrowth blocks" }
    }
}) {
    private companion object {
        const val SEED = 4786177076396069349L
        const val SENTENCE = "age clear sky"
        const val OVERGROWN = "grownover"
        const val PLAIN = "plainground"
        const val MOST_STEPS = 4
        const val FAR = 5000
        const val REACH = 200
        const val PROBE_HEIGHT = 64
        const val SCANNED = 96
        const val TILE = 32
        const val SCAN_FROM = 48
        const val SCAN_TO = 112
        const val ENOUGH_FOR_A_PATCH = 200
        val CHANGED = Regex("filled (\\d+) block")
    }
}

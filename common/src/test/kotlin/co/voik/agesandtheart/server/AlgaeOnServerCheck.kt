package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * Algae lies in quarters: a mat placed as it always was is whole, and a mat comes away one algae a quarter. Set
 * `strict`, since a mat in the open washes away at once and this is about the block, not where it grows.
 */
@Tags(NEEDS_SERVER)
class AlgaeOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    fun inWorld(command: String) = server.run("execute in minecraft:overworld run $command")

    test("a mat set as it always was is a whole one") {
        inWorld("forceload add 0 0")
        inWorld("setblock 2 $CHECK_Y 2 agesandtheart:algae strict")
        val whole = inWorld("execute if block 2 $CHECK_Y 2 agesandtheart:algae[segment_amount=4]")
        check(whole.startsWith("Test passed")) { "a mat set with no quarters said was not whole: $whole" }
    }

    test("a mat comes away one algae for each quarter") {
        inWorld("forceload add 0 0")
        inWorld("setblock 4 $CHECK_Y 4 agesandtheart:algae[segment_amount=3] strict")
        val dropped = inWorld("loot spawn 4 ${CHECK_Y + 2} 4 mine 4 $CHECK_Y 4")
        check(dropped.contains("3") && dropped.contains("Algae", ignoreCase = true)) { "three quarters of a mat dropped: $dropped" }
    }
})

private const val CHECK_Y = 200

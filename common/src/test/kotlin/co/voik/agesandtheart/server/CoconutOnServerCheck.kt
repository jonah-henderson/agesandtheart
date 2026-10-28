package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * A buried coconut grows a palm, and one left open does not. A server check because the sprouting is a random
 * tick and the palm a feature placed into a real level. Both coconuts sit in a sandstone platform high over the
 * spawn chunk — sand would fall away from them — with glowstone beside each so the hour does not matter, and random
 * ticks are run fast past the point where the buried one would have sprouted many times over.
 */
@Tags(NEEDS_SERVER)
class CoconutOnServerCheck : FunSpec({

    val server = DrivenServer.shared

    fun passes(command: String) = server.run(command).startsWith("Test passed")

    /** A coconut shut in on every side but up, with sand over it only where [buried]. */
    fun plant(x: Int, buried: Boolean) {
        server.run("fill ${x - 1} ${COCONUT_Y - 1} ${Z - 1} ${x + 1} $COCONUT_Y ${Z + 1} minecraft:sandstone")
        server.run("setblock $x $COCONUT_Y $Z agesandtheart:coconut")
        if (buried) server.run("setblock $x $GROUND_Y $Z minecraft:sand")
        server.run("setblock ${x + 1} $GROUND_Y $Z minecraft:glowstone")
    }

    test("a buried coconut grows a palm on the ground over it, and one left open stays a coconut") {
        server.run("forceload add $BURIED_X $Z $OPEN_X $Z")
        plant(BURIED_X, buried = true)
        plant(OPEN_X, buried = false)
        server.run("gamerule random_tick_speed $QUICK_TICKS")
        try {
            server.sprint(SPRINTED_TICKS)
        } finally {
            server.run("gamerule random_tick_speed $VANILLA_TICKS")
        }
        val grewATrunk = passes("execute if block $BURIED_X $TREE_Y $Z agesandtheart:palm_log")
        val filledItsPlace = passes("execute if block $BURIED_X $COCONUT_Y $Z minecraft:sand")
        val openOneStayed = passes("execute if block $OPEN_X $COCONUT_Y $Z agesandtheart:coconut")
        server.run("forceload remove all")
        check(grewATrunk) { "no palm trunk stands over the buried coconut" }
        check(filledItsPlace) { "the buried coconut's place was not filled with the ground over it" }
        check(openOneStayed) { "the coconut left open sprouted, or went" }
    }
})

private const val BURIED_X = 40
private const val OPEN_X = 56
private const val Z = 40

/** High in open air, so the palm has room and sky over it. */
private const val COCONUT_Y = 200
private const val GROUND_Y = COCONUT_Y + 1
private const val TREE_Y = COCONUT_Y + 2

/** One random tick in fourteen sprouts it, so at this speed two hundred ticks miss with odds near one in ten thousand. */
private const val QUICK_TICKS = 4096
private const val VANILLA_TICKS = 3
private const val SPRINTED_TICKS = 200

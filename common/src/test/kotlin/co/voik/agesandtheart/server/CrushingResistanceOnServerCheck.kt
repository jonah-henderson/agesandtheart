package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * That a brewing stand makes crushing resistance from an awkward potion and a sea pickle, and lengthens it
 * with redstone — the brewing is datapack JSON, which only a server reads.
 */
@Tags(NEEDS_SERVER)
class CrushingResistanceOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    fun loaded(x: Int) {
        server.run("forceload add $x 0")
        val isThere = (1..LOAD_TRIES).any {
            Thread.sleep(LOAD_WAIT_MILLIS)
            server.run("execute if loaded $x 0 0").startsWith("Test passed")
        }
        check(isThere) { "the chunk at ($x, 0) never loaded" }
    }

    fun potion(named: String) = "minecraft:potion[minecraft:potion_contents={potion:\"$named\"}]"

    fun brews(from: String, reagent: String, into: String): Boolean {
        server.run("setblock $STAND_X $STAND_Y 0 minecraft:brewing_stand")
        server.run("item replace block $STAND_X $STAND_Y 0 container.0 with ${potion(from)}")
        server.run("item replace block $STAND_X $STAND_Y 0 container.3 with minecraft:$reagent")
        server.run("item replace block $STAND_X $STAND_Y 0 container.4 with minecraft:blaze_powder")
        server.run("tick sprint $ONE_BREW_AND_A_LITTLE")
        val made = server.untilPasses("execute if items block $STAND_X $STAND_Y 0 container.0 ${potion(into)}")
        return made.startsWith("Test passed")
    }

    test("a sea pickle brews it, and redstone lengthens it") {
        loaded(STAND_X)
        check(brews("minecraft:awkward", "sea_pickle", "agesandtheart:crushing_resistance")) {
            "an awkward potion and a sea pickle: ${server.run("data get block $STAND_X $STAND_Y 0 Items")}"
        }
        check(brews("agesandtheart:crushing_resistance", "redstone", "agesandtheart:long_crushing_resistance")) {
            "crushing resistance and redstone: ${server.run("data get block $STAND_X $STAND_Y 0 Items")}"
        }
    }
}) {
    private companion object {
        const val STAND_X = 40
        const val STAND_Y = 250

        /** A brew is 400 ticks. */
        const val ONE_BREW_AND_A_LITTLE = 500

        const val LOAD_TRIES = 20
        const val LOAD_WAIT_MILLIS = 500L
    }
}

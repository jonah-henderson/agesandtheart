package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * A link into an Age whose book said `link in` arrives at what it named — on the sea's surface, beside a village —
 * rather than where an Age that said nothing puts you. Read off `/age arrival`, which asks what a link asks.
 */
@Tags(NEEDS_SERVER)
class LinkingInOnServerCheck : FunSpec({

    val server = DrivenServer.shared

    fun arrivalIn(age: String, sentence: String): List<Int> {
        server.run("age write $age $SEED $sentence")
        return server.ask("arrival", age).getAsJsonArray("at").map { it.asInt }
    }

    test("a link in the sea arrives on its surface, and a link in a village somewhere else than the ordinary arrival") {
        val ordinary = arrivalIn(ORDINARY, "age clear sky")
        val (x, y, z) = arrivalIn(AT_SEA, "age link in sea clear sky")
        server.run("execute in agesandtheart:$AT_SEA run forceload add $x $z")
        val onWater = server.run("execute in agesandtheart:$AT_SEA run execute if block $x ${y - 1} $z minecraft:water")
        server.run("execute in agesandtheart:$AT_SEA run forceload remove all")
        check(onWater.startsWith("Test passed")) { "a link in the sea arrived at $x $y $z, not over water: $onWater" }
        val village = arrivalIn(BY_A_VILLAGE, "age link in villages clear sky")
        check(village != ordinary) { "a link in a village arrived where the ordinary link does, at $village" }
    }

    test("a link in a cave biome arrives in the open underground, and one in a surface biome on the surface") {
        val (x, y, z) = arrivalIn(IN_A_CAVE, "age link in lush_caves clear sky")
        val surface = arrivalIn(IN_A_JUNGLE, "age link in jungle clear sky")[1]
        check(y < CAVE_DEPTH) { "a link in the lush caves arrived at $x $y $z, not under ground" }
        check(surface >= SURFACE_AT_LEAST) { "a link in a jungle arrived at height $surface, under ground" }
        server.run("execute in agesandtheart:$IN_A_CAVE run forceload add $x $z")
        fun isAir(at: Int) = server.run("execute in agesandtheart:$IN_A_CAVE run execute if block $x $at $z minecraft:air").startsWith("Test passed")
        val standing = isAir(y) && isAir(y + 1) && !isAir(y - 1)
        server.run("execute in agesandtheart:$IN_A_CAVE run forceload remove all")
        check(standing) { "a link in the lush caves arrived at $x $y $z, with no room to stand there" }
    }
})

private const val SEED = 4786177076396069349L
private const val ORDINARY = "linkplain"
private const val AT_SEA = "linkatsea"
private const val BY_A_VILLAGE = "linkbyvillage"
private const val IN_A_CAVE = "linkinacave"
private const val IN_A_JUNGLE = "linkinajungle"

/** Below the lowest an ordinary surface goes, and above it. */
private const val CAVE_DEPTH = 50
private const val SURFACE_AT_LEAST = 60

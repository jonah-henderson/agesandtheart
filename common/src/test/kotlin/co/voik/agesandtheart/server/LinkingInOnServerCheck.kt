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
})

private const val SEED = 4786177076396069349L
private const val ORDINARY = "linkplain"
private const val AT_SEA = "linkatsea"
private const val BY_A_VILLAGE = "linkbyvillage"

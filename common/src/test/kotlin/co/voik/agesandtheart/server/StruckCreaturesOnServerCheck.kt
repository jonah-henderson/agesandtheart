package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * That a creature an Age's book struck is never made there, by any path rather than only natural spawning. A
 * summon stands in for the paths that are not the spawner — a shrieker's warden, a spawner, a structure's
 * inhabitants — since every one of them goes through the same `addFreshEntity`.
 */
@Tags(NEEDS_SERVER)
class StruckCreaturesOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    /**
     * Summons [creature] in [age], once the chunk is there, and answers what the level holds of it afterwards.
     * `/summon` says it summoned whatever the level then refused, so the level is asked.
     */
    fun summonIn(age: String, creature: String): String {
        server.run("execute in agesandtheart:$age run forceload add 0 0")
        server.waitUntil("the chunk at (0, 0) in $age loading") {
            server.run("execute in agesandtheart:$age if loaded 0 0 0").startsWith("Test passed")
        }
        server.run("execute in agesandtheart:$age run summon minecraft:$creature 0 200 0 {NoAI:1b}")
        return server.run("execute in agesandtheart:$age if entity @e[type=minecraft:$creature,distance=0..]")
    }

    fun wasSummoned(said: String) = said.startsWith("Test passed")

    test("a struck creature is refused whatever brings it, and one the book left alone is not") {
        server.run("age write $NO_MONSTERS 7 age no monsters")
        server.run("age write $NO_ZOMBIE 7 age no zombie")
        server.run("age write $SAID_NOTHING 7 age")
        for (struck in listOf("warden", "zombie", "creeper")) {
            val said = summonIn(NO_MONSTERS, struck)
            check(!wasSummoned(said)) { "a $struck was made in an Age with no monsters: $said" }
        }
        val cow = summonIn(NO_MONSTERS, "cow")
        check(wasSummoned(cow)) { "a cow was refused in an Age with no monsters: $cow" }
        check(!wasSummoned(summonIn(NO_ZOMBIE, "zombie"))) { "a zombie was made in an Age with no zombie" }
        val skeleton = summonIn(NO_ZOMBIE, "skeleton")
        check(wasSummoned(skeleton)) { "a skeleton was refused in an Age with no zombie: $skeleton" }
        val warden = summonIn(SAID_NOTHING, "warden")
        check(wasSummoned(warden)) { "a warden was refused in an Age that said nothing of creatures: $warden" }
    }

    test("what an `only` leaves out is refused, a villager with no word of its own among it") {
        server.run("age write $ONLY_COW 7 age only cow")
        check(!wasSummoned(summonIn(ONLY_COW, "villager"))) { "a villager was made in an Age of only cows" }
        check(wasSummoned(summonIn(ONLY_COW, "cow"))) { "a cow was refused in an Age of only cows" }
        val villager = summonIn(SAID_NOTHING, "villager")
        check(wasSummoned(villager)) { "a villager was refused in an Age that said nothing of creatures: $villager" }
    }
}) {
    private companion object {
        const val NO_MONSTERS = "struckmonsters"
        const val NO_ZOMBIE = "struckzombie"
        const val SAID_NOTHING = "struckcontrol"
        const val ONLY_COW = "struckonlycow"
    }
}

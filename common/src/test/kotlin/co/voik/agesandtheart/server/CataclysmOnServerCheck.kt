package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * That `/age cataclysm` sets an asteroid going in an Age and calls it off, that its links fail from the impact, and
 * that the scorch burns a tree down to charred stumps on burnt ground and leaves stone alone.
 *
 * The sky, the heat and the dust need a player in the Age and are the walk's to judge.
 */
@Tags(NEEDS_SERVER)
class CataclysmOnServerCheck : FunSpec({
    val server = DrivenServer.shared
    val doomed = "doomed"

    fun inTheAge(command: String) = server.run("execute in agesandtheart:$doomed run $command")
    fun holds(x: Int, z: Int, block: String) = inTheAge("execute if block $x $HIGH $z $block").startsWith("Test passed")

    beforeSpec {
        server.run("age compose $doomed 5 landmass=gentle sea=minecraft:water")
    }

    test("an asteroid set going approaches, and its links hold until the impact") {
        val report = inTheAge("age cataclysm asteroid")
        check("An asteroid is coming" in report) { "The asteroid was not set going:\n$report" }
        check("now: approach" in report) { "It did not begin with its approach:\n$report" }
        check("its links hold" in report) { "The links failed before the impact:\n$report" }
    }

    test("from the impact on, its links fail") {
        val report = inTheAge("age cataclysm asteroid travel")
        check("now: travel" in report) { "Starting from the travel did not:\n$report" }
        check("its links fail" in report) { "The links held after the impact:\n$report" }
    }

    test("cleared, nothing is coming") {
        inTheAge("age cataclysm clear")
        val report = inTheAge("age cataclysm")
        check("no end coming" in report) { "The end was still coming:\n$report" }
    }

    test("the scorch burns a tree to a charred stump on burnt ground, and leaves stone alone") {
        inTheAge("forceload add 0 0")
        server.waitUntil("the doomed Age's first chunk loads") {
            inTheAge("execute if loaded 0 0 0").startsWith("Test passed")
        }
        inTheAge("setblock 2 $HIGH 2 minecraft:grass_block")
        inTheAge("setblock 2 ${HIGH + 1} 2 minecraft:oak_log")
        inTheAge("setblock 2 ${HIGH + 2} 2 minecraft:oak_leaves")
        inTheAge("setblock 4 $HIGH 4 minecraft:stone")
        inTheAge("setblock 4 ${HIGH + 1} 4 minecraft:short_grass")

        val report = inTheAge("execute positioned 2 $HIGH 2 run age cataclysm scorch")
        check("Scorched" in report) { "Nothing was scorched:\n$report" }

        check(holds(2, 2, "minecraft:coarse_dirt")) { "The grass under the tree was not burnt" }
        check(inTheAge("execute if block 2 ${HIGH + 1} 2 minecraft:basalt").startsWith("Test passed")) { "The trunk was not charred" }
        check(inTheAge("execute if block 2 ${HIGH + 2} 2 minecraft:air").startsWith("Test passed")) { "The leaves did not burn away" }
        check(holds(4, 4, "minecraft:stone")) { "The stone burned" }
        check(inTheAge("execute if block 4 ${HIGH + 1} 4 minecraft:air").startsWith("Test passed")) { "The grass on the stone did not burn" }
    }
}) {
    private companion object {
        /** Above any gentle land, so what is laid here is the top of its column. */
        const val HIGH = 200
    }
}

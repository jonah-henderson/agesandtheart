package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * A world floating over nothing — the End's rock, skyborne — has no sea unless a book names one, and then a
 * shallow one on bedrock at the bottom of the world.
 */
@Tags(NEEDS_SERVER, NEEDS_TIME)
class SeaUnderTheVoidOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    fun inAge(age: String, command: String) = server.run("execute in agesandtheart:$age run $command")

    /** How many of [block] fill the bottom band of [age]'s origin chunk between [fromY] and [toY], counted by turning them to glass. */
    fun countOf(age: String, block: String, fromY: Int, toY: Int): Int {
        inAge(age, "forceload add 0 0 15 15")
        val said = inAge(age, "fill 0 $fromY 0 15 $toY 15 minecraft:glass replace $block")
        return Regex("""filled (\d+)""").find(said)?.groupValues?.get(1)?.toInt() ?: 0
    }

    fun write(age: String, book: String) {
        val said = server.run("age write $age 7 $book")
        check("Created Age" in said) { "'$book' was not written: $said" }
    }

    test("the End's rock with a sea named has one at the bottom of the world, on bedrock") {
        write("endsea", "age end landmass water sea")
        check(countOf("endsea", "minecraft:water", FLOOR + 1, FLOOR + SEA_DEPTH - 1) > 0) { "no sea at the bottom of an End Age that named one" }
        check(countOf("endsea", "minecraft:bedrock", FLOOR, FLOOR) > 0) { "no bedrock under the End Age's sea" }
    }

    test("the End's rock with no sea named has none") {
        write("endnosea", "age end landmass")
        check(countOf("endnosea", "minecraft:water", FLOOR, FLOOR + SEA_DEPTH) == 0) { "an End Age that named no sea had one" }
    }

    test("skyborne land with a sea named has it at the bottom of the world") {
        write("skysea", "age skyborne landmass water sea")
        check(countOf("skysea", "minecraft:water", FLOOR + 1, FLOOR + SEA_DEPTH - 1) > 0) { "no sea at the bottom of a skyborne Age that named one" }
    }
})

/** The bottom of every Age's world, and how deep a sea laid on it stands, floor included. */
private const val FLOOR = -64
private const val SEA_DEPTH = 16

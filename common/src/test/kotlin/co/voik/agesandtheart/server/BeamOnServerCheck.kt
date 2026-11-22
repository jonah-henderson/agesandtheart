package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * Astrite's optics at noon (`optics/`): a collector's diffuse light focused by an emitter into a laser, bounced over a
 * wall, split and merged, light shared across a conduit network, colours mixed in one, stripes keeping conduits apart,
 * and redstone putting an emitter out.
 */
@Tags(NEEDS_SERVER)
class BeamOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    fun inWorld(command: String) = server.run("execute in minecraft:overworld run $command")

    fun passes(test: String) = inWorld("execute $test").startsWith("Test passed")

    fun set(x: Int, z: Int, block: String) = inWorld("setblock $x $Y $z $block")

    fun setAt(x: Int, y: Int, z: Int, block: String) = inWorld("setblock $x $y $z $block")

    fun lit(x: Int, z: Int) = passes("if block $x $Y $z agesandtheart:beam_receiver[powered=true]")

    fun bench(x: Int) {
        inWorld("forceload add ${x - 2} ${Z - 10} ${x + 12} ${Z + 10}")
        server.waitUntil("the bench's chunks loading") { passes("if loaded $x $Y $Z") }
        inWorld("time set noon")
        inWorld("weather clear")
    }

    /** A collector at [x] shining east into the back of an emitter, whose laser a reflector turns south to a receiver. */
    fun focusedBench(x: Int) {
        bench(x)
        set(x, Z, "agesandtheart:sunlight_collector[facing=east]")
        set(x + 2, Z, "agesandtheart:astrite_emitter[facing=east]")
        set(x + 7, Z, "agesandtheart:reflector[mirror=west_south]")
        set(x + 7, Z + 4, "agesandtheart:beam_receiver")
    }

    test("a collector's light focused by an emitter lights a receiver past a reflector") {
        focusedBench(FOCUSED_X)
        server.sprint(SETTLING_TICKS)
        check(lit(FOCUSED_X + 7, Z + 4)) { "the receiver past the reflector was not lit" }
    }

    test("four reflectors bounce a laser up, over a wall and down again") {
        val x = BOUNCED_X
        bench(x)
        set(x, Z, "agesandtheart:sunlight_collector[facing=east]")
        set(x + 2, Z, "agesandtheart:astrite_emitter[facing=east]")
        setAt(x + 4, Y, Z, "agesandtheart:reflector[mirror=west_up]")
        setAt(x + 4, Y + 3, Z, "agesandtheart:reflector[mirror=down_east]")
        for (height in 0..2) setAt(x + 6, Y + height, Z, "minecraft:stone")
        setAt(x + 8, Y + 3, Z, "agesandtheart:reflector[mirror=west_down]")
        setAt(x + 8, Y, Z, "agesandtheart:reflector[mirror=up_east]")
        set(x + 10, Z, "agesandtheart:beam_receiver")
        server.sprint(SETTLING_TICKS)
        check(lit(x + 10, Z)) { "the receiver past the wall was not lit" }
    }

    test("a splitter halves a laser between two receivers, which a doubled laser lights") {
        val x = SPLIT_X
        bench(x)
        for (along in 0..2) set(x + along, Z, "agesandtheart:optical_conduit")
        set(x, Z - 1, "agesandtheart:sunlight_collector[facing=south]")
        set(x + 1, Z + 1, "agesandtheart:astrite_emitter[facing=south]")
        set(x + 1, Z + 4, "agesandtheart:beam_splitter[faces=north_east]")
        set(x + 1, Z + 7, "agesandtheart:beam_receiver")
        set(x + 4, Z + 4, "agesandtheart:beam_receiver")
        server.sprint(SETTLING_TICKS)
        check(!lit(x + 1, Z + 7) && !lit(x + 4, Z + 4)) { "half a full laser lit a receiver past a splitter" }
        set(x + 2, Z - 1, "agesandtheart:sunlight_collector[facing=south]")
        server.sprint(SETTLING_TICKS)
        check(lit(x + 1, Z + 7)) { "the half of a doubled laser carrying straight on did not light its receiver" }
        check(lit(x + 4, Z + 4)) { "the half of a doubled laser turned by a splitter did not light its receiver" }
    }

    test("a laser split and brought back together by a merger is whole again") {
        val x = MERGED_X
        bench(x)
        set(x, Z, "agesandtheart:sunlight_collector[facing=east]")
        set(x + 2, Z, "agesandtheart:astrite_emitter[facing=east]")
        set(x + 4, Z, "agesandtheart:beam_splitter[faces=west_north]")
        set(x + 4, Z - 4, "agesandtheart:reflector[mirror=south_east]")
        set(x + 8, Z, "agesandtheart:reflector[mirror=west_north]")
        set(x + 8, Z - 4, "agesandtheart:beam_merger[faces=north_west]")
        set(x + 8, Z - 7, "agesandtheart:beam_receiver")
        server.sprint(SETTLING_TICKS)
        check(lit(x + 8, Z - 7)) { "the two halves of a laser merged again did not light a receiver" }
        set(x + 4, Z - 4, "minecraft:air")
        server.sprint(SETTLING_TICKS)
        check(!lit(x + 8, Z - 7)) { "half a laser through a merger lit a receiver" }
    }

    test("redstone puts an emitter out") {
        focusedBench(SWITCHED_X)
        inWorld("setblock ${SWITCHED_X + 2} ${Y + 1} $Z minecraft:redstone_block")
        server.sprint(SETTLING_TICKS)
        check(!lit(SWITCHED_X + 7, Z + 4)) { "an emitter beside a redstone block still lit its receiver" }
    }

    test("one collector's light shared by two emitters lights neither receiver, and two collectors' lights both") {
        val x = SHARED_X
        bench(x)
        for (along in 0..3) set(x + along, Z, "agesandtheart:optical_conduit")
        set(x, Z - 1, "agesandtheart:sunlight_collector[facing=south]")
        for (emitterX in listOf(x + 1, x + 3)) {
            set(emitterX, Z + 1, "agesandtheart:astrite_emitter[facing=south]")
            set(emitterX, Z + 5, "agesandtheart:beam_receiver")
        }
        server.sprint(SETTLING_TICKS)
        check(!lit(x + 1, Z + 5) && !lit(x + 3, Z + 5)) { "half a collector's light lit a receiver" }
        set(x + 2, Z - 1, "agesandtheart:sunlight_collector[facing=south]")
        server.sprint(SETTLING_TICKS)
        check(lit(x + 1, Z + 5) && lit(x + 3, Z + 5)) { "each emitter's half of two collectors' light did not light its receiver" }
    }

    test("red and lime light mixed in a network light a receiver asking yellow, and not one asking red") {
        val x = MIXED_X
        bench(x)
        for (along in 0..4) set(x + along, Z, "agesandtheart:optical_conduit")
        for ((inputX, glass) in listOf(x to "red", x + 2 to "lime")) {
            set(inputX, Z + 4, "agesandtheart:sunlight_collector[facing=north]")
            set(inputX, Z + 3, "minecraft:${glass}_stained_glass")
            set(inputX, Z + 1, "agesandtheart:beam_receiver")
        }
        set(x + 4, Z + 1, "agesandtheart:beam_receiver{crystal:\"yellow\"}")
        set(x + 4, Z - 1, "agesandtheart:beam_receiver[facing=south]{crystal:\"red\"}")
        server.sprint(SETTLING_TICKS)
        check(lit(x + 4, Z + 1)) { "red and lime light in one network did not light a receiver asking yellow" }
        check(!lit(x + 4, Z - 1)) { "a receiver asking red was lit by red and lime light mixed" }
    }

    test("conduits of different stripes do not join, and do once they share one") {
        val x = STRIPED_X
        bench(x)
        set(x, Z - 1, "agesandtheart:sunlight_collector[facing=south]")
        set(x, Z, "agesandtheart:optical_conduit[stripe=red]")
        set(x + 1, Z, "agesandtheart:optical_conduit[stripe=blue]")
        set(x + 1, Z + 1, "agesandtheart:astrite_emitter[facing=south]")
        set(x + 1, Z + 5, "agesandtheart:beam_receiver")
        server.sprint(SETTLING_TICKS)
        check(!lit(x + 1, Z + 5)) { "light crossed from a red conduit into a blue one" }
        set(x + 1, Z, "agesandtheart:optical_conduit[stripe=red]")
        server.sprint(SETTLING_TICKS)
        check(lit(x + 1, Z + 5)) { "light did not cross between two red conduits" }
    }
})

private const val Y = 220
private const val Z = -9200
private const val FOCUSED_X = -9200
private const val SWITCHED_X = -9300
private const val SHARED_X = -9400
private const val MIXED_X = -9500
private const val STRIPED_X = -9600
private const val BOUNCED_X = -9700
private const val SPLIT_X = -9800
private const val MERGED_X = -9900

/** Light is passed on every tick, and this is room to spare for a chain of three. */
private const val SETTLING_TICKS = 10

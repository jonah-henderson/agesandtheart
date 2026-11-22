package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * An astrite emitter in noon sunlight (`optics/Beam`): its beam turned by a reflector into a receiver, coloured by
 * stained glass for a receiver asking a colour, and put out by redstone.
 */
@Tags(NEEDS_SERVER)
class BeamOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    fun inWorld(command: String) = server.run("execute in minecraft:overworld run $command")

    fun passes(test: String) = inWorld("execute $test").startsWith("Test passed")

    /** An emitter at [x] shining east, a reflector five on turning it south, and a receiver four beyond that. */
    fun bench(x: Int, receiverData: String = "") {
        inWorld("forceload add $x $Z ${x + 8} ${Z + 8}")
        server.waitUntil("the bench's chunks loading") { passes("if loaded $x $Y $Z") }
        inWorld("time set noon")
        inWorld("weather clear")
        inWorld("setblock $x $Y $Z agesandtheart:astrite_emitter[facing=east]")
        inWorld("setblock ${x + 5} $Y $Z agesandtheart:reflector[mirror=west_south]")
        inWorld("setblock ${x + 5} $Y ${Z + 4} agesandtheart:beam_receiver$receiverData")
    }

    fun receiverLit(x: Int) = passes("if block ${x + 5} $Y ${Z + 4} agesandtheart:beam_receiver[powered=true]")

    test("a beam turned by a reflector lights a receiver") {
        bench(PLAIN_X)
        server.sprint(SETTLING_TICKS)
        check(receiverLit(PLAIN_X)) { "the receiver past the reflector was not lit" }
    }

    test("a receiver asking red is lit only through red glass") {
        bench(RED_X, receiverData = "{crystal:\"red\"}")
        server.sprint(SETTLING_TICKS)
        check(!receiverLit(RED_X)) { "a white beam lit a receiver asking red" }
        inWorld("setblock ${RED_X + 2} $Y $Z minecraft:red_stained_glass")
        server.sprint(SETTLING_TICKS)
        check(receiverLit(RED_X)) { "a beam through red glass did not light a receiver asking red" }
    }

    test("redstone puts an emitter out") {
        bench(SWITCHED_X)
        inWorld("setblock $SWITCHED_X ${Y + 1} $Z minecraft:redstone_block")
        server.sprint(SETTLING_TICKS)
        check(!receiverLit(SWITCHED_X)) { "an emitter beside a redstone block still lit its receiver" }
    }
})

private const val Y = 220
private const val Z = -9200
private const val PLAIN_X = -9200
private const val RED_X = -9300
private const val SWITCHED_X = -9400

/** The beam is followed every ten ticks and a receiver stays lit twelve, so forty is several looks. */
private const val SETTLING_TICKS = 40

package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * An Age of stone turns to deepslate at depth, as vanilla's overworld does, whichever landform shapes it; an Age
 * of anything else keeps its own rock all the way down.
 */
@Tags(NEEDS_SERVER, NEEDS_TIME)
class DeepslateOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    /** How much deepslate stands deep under the origin of [age], counted by turning it to stone. */
    fun deepslateUnder(age: String): Int {
        // A forceload returns once its chunks are generated, surface rules and all.
        server.run("execute in agesandtheart:$age run forceload add 0 0 15 15")
        val said = server.run("execute in agesandtheart:$age run fill 0 $DEEP_FROM 0 15 $DEEP_TO 15 minecraft:stone replace minecraft:deepslate")
        return Regex("""filled (\d+)""").find(said)?.groupValues?.get(1)?.toInt() ?: 0
    }

    test("a cratered Age of stone is deepslate at depth") {
        server.run("age write crateredstone 7 age cratered landmass")
        check(deepslateUnder("crateredstone") > 0) { "no deepslate deep under a cratered Age" }
    }

    test("an Age of blackstone keeps blackstone all the way down") {
        server.run("age write blackstonedeep 7 age blackstone cratered landmass")
        check(deepslateUnder("blackstonedeep") == 0) { "deepslate repainted an Age of blackstone" }
    }
})

private const val DEEP_FROM = -48
private const val DEEP_TO = -41

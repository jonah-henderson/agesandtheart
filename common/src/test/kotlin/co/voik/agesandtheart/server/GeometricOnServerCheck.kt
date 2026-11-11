package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * The geometric landform built of a formation the server's registry holds. Offline there is no registry and every
 * geometric landform is the pyramids, so only a server can say the lookup reaches the formations at all.
 */
@Tags(NEEDS_SERVER)
class GeometricOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    /** The rock of each column in a square about the origin, where the shapes stand thickest. */
    fun rockOf(name: String, shape: String): List<String> {
        server.run("age compose $name $SEED landmass=geometric landmass.shape=$shape")
        return COLUMNS.map { (x, z) -> server.ask("probe", "$name $x $z").get("rock").asString }
    }

    /** The highest rock a probe reports, off its `y=from..to` runs. */
    fun topOf(rock: String): Int = rock.substringAfterLast("..").toInt()

    test("another formation's shapes stand where the pyramids would") {
        val pyramids = rockOf("geompyr", "agesandtheart:pyramids")
        val obelisks = rockOf("geomobel", "agesandtheart:obelisks")
        check(pyramids != obelisks) { "an obelisk landform came out as the pyramids: no formation was found" }
        check(obelisks.any { topOf(it) > PLAIN_TOP }) { "no obelisk stood on the plain" }
    }

    test("a dug formation is cut into the plain") {
        val pits = rockOf("geompits", "agesandtheart:pits")
        check(pits.all { topOf(it) <= PLAIN_TOP }) { "something stood on a plain of pits" }
        check(pits.any { topOf(it) < PLAIN_TOP }) { "no pit was dug" }
    }
})

private const val SEED = 4242L
private const val PLAIN_TOP = 63
private val COLUMNS = (-40..40 step 4).flatMap { x -> (-40..40 step 4).map { z -> x to z } }

package co.voik.agesandtheart.age.consequence

import co.voik.agesandtheart.server.DrivenServer
import co.voik.agesandtheart.server.NEEDS_SERVER
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * `/age instability <name> force` shows a manifestation in an Age whatever it drew — a coherent one included,
 * which draws nothing and so has no ladder ceilings of its own — and `clear` takes it away again.
 */
@Tags(NEEDS_SERVER)
class ForcedManifestationOnServerCheck : FunSpec({

    val server = DrivenServer.shared

    test("a forced manifestation shows in a coherent Age until it is cleared") {
        server.run("age compose forcedcheck 5 landmass=gentle sea=water")
        val before = server.run("age instability forcedcheck")
        check("shows nothing" in before) { "a coherent Age showed something before anything was forced:\n$before" }

        server.run("age instability forcedcheck force sky_tears 2")
        val forced = server.run("age instability forcedcheck")
        check("sky_tears ×2" in forced && "forced: sky_tears×2" in forced) { "forcing two steps of tears showed:\n$forced" }

        val toldCapped = server.run("age instability forcedcheck force sky_tears 9")
        check("now shows sky_tears ×3" in toldCapped) { "forcing past the ladder did not say what it held to:\n$toldCapped" }
        val capped = server.run("age instability forcedcheck")
        check("sky_tears ×3" in capped) { "a forced dial was not held to its ladder:\n$capped" }

        server.run("age instability forcedcheck clear")
        val cleared = server.run("age instability forcedcheck")
        check("shows nothing" in cleared && "nothing forced" in cleared) { "clearing left:\n$cleared" }
    }

    test("a pull set by hand shows either way, until it is cleared") {
        server.run("age compose pullcheck 5 landmass=gentle sea=water")
        server.run("age instability pullcheck gravity 1.75")
        val heavy = server.run("age instability pullcheck")
        check("gravity set to 175%" in heavy) { "a heavy pull was not reported:\n$heavy" }
        server.run("age instability pullcheck gravity 0.5")
        val light = server.run("age instability pullcheck")
        check("gravity set to 50%" in light) { "a light pull was not reported:\n$light" }
        server.run("age instability pullcheck clear")
        val cleared = server.run("age instability pullcheck")
        check("gravity set to" !in cleared) { "clearing left a pull:\n$cleared" }
    }

    test("without a name it asks about the Age stood in, and says so when there is none") {
        val unnamed = server.run("age instability")
        check("Not standing in an Age" in unnamed) { "the console is in no Age, and was answered:\n$unnamed" }
    }
})

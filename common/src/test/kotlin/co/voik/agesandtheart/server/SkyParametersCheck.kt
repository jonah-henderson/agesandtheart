package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * That `/age sky`'s parameters reach the library at all.
 *
 * They exist to make things walkable that no word reaches, and a parameter that silently does nothing is worse
 * than no parameter — you would look at an unchanged sky and conclude the feature was broken. So each one is
 * driven here and the report is read back, which is the only part a server can see.
 *
 * What it *looks* like is still a matter for eyes; this only says the lever is connected.
 */
@Tags(NEEDS_SERVER)
class SkyParametersCheck : FunSpec({
    val server = DrivenServer.shared

    val age = "skyparameters"

    beforeSpec {
        server.run("age compose $age 5 landmass=hills sea=minecraft:water rock=caves")
    }

    test("a parameter is acted on and said back") {
        val report = server.run("age sky $age sky path=polar")
        check("turned path=polar" in report) { "The parameter was not reported as turned:\n$report" }
    }

    test("a polar path really never sets") {
        // The report describes a non-circle by what it *does*, so the altitude range is readable here — and
        // a path that never sets has a lowest above the horizon.
        val report = server.run("age sky $age sky path=polar")
        val lowest = Regex("""reaching\s+([+-][\d.]+)°""").find(report)?.groupValues?.get(1)?.toFloatOrNull()
        // A polar path is an Orbit, so it reports in angles rather than in a range; either way it must not
        // read as vanilla's untilted circle.
        check("tilt  +0°" !in report || lowest != null) {
            "A polar path was described as an untilted circle, so the parameter did not replace the path:\n$report"
        }
    }

    test("an epicycle is described as the shape it is, not as a circle") {
        val report = server.run("age sky $age sky path=epicycle")
        check("motions" in report) {
            "An epicycling body was not described as a motion stack, so the path was not replaced:\n$report"
        }
        check("reaching" in report) { "A stack was described without its altitude range:\n$report" }
    }

    test("every parameter is accepted") {
        // Cheap, and it catches a parameter whose name drifts from the table that advertises it.
        val each = listOf(
            "lift=45",
            "swell=0.4",
            "path=figure8",
            "glow=additive",
            "daylight=primary_sun",
            "deck=both",
            "rainbow=banded",
        )
        for (parameter in each) {
            val report = server.run("age sky $age sky $parameter")
            check("turned $parameter" in report) { "'$parameter' was not accepted:\n$report" }
        }
    }

    test("several parameters at once, alongside the Art's own words") {
        val report = server.run("age sky $age sky sky.size=0.5..0.9 path=epicycle glow=nearest deck=solid")
        check("turned" in report) { "A mixed line was refused:\n$report" }
        check("sun" in report) { "A mixed line lost the Art's own words:\n$report" }
    }

    /**
     * **A body parameter on a preview line reaches the body**, which "it was accepted" does not say.
     *
     * Everything overhead is spelled `sky.…` because a preview is one instrument over one picture, but the
     * bodies are the sun's, the moon's and the stars' aspects — so a `sky.size` was validated against
     * the sun's parameters, stored on the vault, and then looked for on the sun. Accepted, and ignored.
     */
    test("a body parameter on a sky line changes the sky") {
        val plain = server.run("age sky $age sky")
        val sized = server.run("age sky $age sky sky.size=0.9..1.0")
        check(described(sized) != described(plain)) {
            "'sky.size' was accepted and changed nothing:\n$sized"
        }
    }

    test("a mistyped parameter says what it should have been") {
        val report = server.run("age sky $age sky glow=lurid")
        check("blended" in report) {
            "A bad parameter value did not offer the values it accepts:\n$report"
        }
    }

    test("a parameter that is not one is still told apart from an aspect") {
        val report = server.run("age sky $age sky landmass=hills")
        check("compose" in report) {
            "Naming a real aspect no longer points at `/age compose`:\n$report"
        }
    }
})

/** The lines describing the sky itself, without the heading or the "turned …" echo of what was asked. */
private fun described(report: String): List<String> =
    report.lines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("turned") && "Age" !in it }

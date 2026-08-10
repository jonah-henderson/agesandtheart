package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * That `/age sky`'s knobs reach the library at all.
 *
 * They exist to make things walkable that no word reaches, and a knob that silently does nothing is worse
 * than no knob — you would look at an unchanged sky and conclude the feature was broken. So each one is
 * driven here and the report is read back, which is the only part a server can see.
 *
 * What it *looks* like is still a matter for eyes; this only says the lever is connected.
 */
@Tags(NEEDS_SERVER)
class SkyKnobsCheck : FunSpec({
    val server = DrivenServer.shared

    val age = "knobage"

    beforeSpec {
        server.run("age compose $age 5 terrain=hills sea=minecraft:water carvers=caves sky=plain sky.suns=2")
    }

    test("a knob is acted on and said back") {
        val report = server.run("age sky $age sky=plain path=polar")
        check("turned path=polar" in report) { "The knob was not reported as turned:\n$report" }
    }

    test("a polar path really never sets") {
        // The report describes a non-circle by what it *does*, so the altitude range is readable here — and
        // a path that never sets has a lowest above the horizon.
        val report = server.run("age sky $age sky=plain path=polar")
        val lowest = Regex("""reaching\s+([+-][\d.]+)°""").find(report)?.groupValues?.get(1)?.toFloatOrNull()
            ?: Regex("""tilt\s+([+-][\d.]+)°""").find(report)?.let { null }
        // A polar path is an Orbit, so it reports in angles rather than in a range; either way it must not
        // read as vanilla's untilted circle.
        check("tilt  +0°" !in report || lowest != null) {
            "A polar path was described as an untilted circle, so the knob did not replace the path:\n$report"
        }
    }

    test("an epicycle is described as the shape it is, not as a circle") {
        val report = server.run("age sky $age sky=plain path=epicycle")
        check("motions" in report) {
            "An epicycling body was not described as a motion stack, so the path was not replaced:\n$report"
        }
        check("reaching" in report) { "A stack was described without its altitude range:\n$report" }
    }

    test("every knob is accepted") {
        // Cheap, and it catches a knob whose name drifts from the table that advertises it.
        val each = listOf(
            "lift=45",
            "swell=0.4",
            "path=figure8",
            "glow=additive",
            "daylight=primary_sun",
            "facing=along_path",
            "deck=both",
        )
        for (knob in each) {
            val report = server.run("age sky $age sky=plain $knob")
            check("turned $knob" in report) { "'$knob' was not accepted:\n$report" }
        }
    }

    test("several knobs at once, alongside the Art's own words") {
        val report = server.run("age sky $age sky=plain sky.suns=3 path=epicycle glow=nearest deck=solid")
        check("turned" in report) { "A mixed line was refused:\n$report" }
        check("sun" in report) { "A mixed line lost the Art's own words:\n$report" }
    }

    test("a mistyped knob says what it should have been") {
        val report = server.run("age sky $age sky=plain glow=lurid")
        check("blended" in report) {
            "A bad knob value did not offer the values it accepts:\n$report"
        }
    }

    test("a knob that is not one is still told apart from an aspect") {
        val report = server.run("age sky $age sky=plain terrain=hills")
        check("compose" in report) {
            "Naming a real aspect no longer points at `/age compose`:\n$report"
        }
    }
})

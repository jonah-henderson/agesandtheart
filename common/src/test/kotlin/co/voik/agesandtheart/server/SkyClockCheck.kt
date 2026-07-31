package co.voik.agesandtheart.server

import co.voik.agesandtheart.age.AgeCommand
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * Whether an Age has a day at all — the regression being a runtime level whose clock never advances, so
 * the sun does not move and there are no stars at any hour.
 *
 * Reads `/age sky` rather than `/time query`, which cannot tell the two apart: it reports the server's
 * clock for whichever clock the dimension type names, following or not.
 */
@Tags(NEEDS_SERVER)
class SkyClockCheck : FunSpec({
    val server = DrivenServer.shared

    /** Ticks either side of the requested time that a reading may land on, the server ticking as we ask. */
    val slack = 200L

    fun clockOf(age: String): Long {
        val report = server.run("age sky $age")
        val reading = report.lineSequence()
            .mapNotNull { line -> line.substringAfter("${AgeCommand.CLOCK_LABEL} ", missingDelimiterValue = "").trim().toLongOrNull() }
            .firstOrNull()
        check(reading != null) { "'/age sky $age' reported no clock, so this check cannot see the thing it guards:\n$report" }
        return reading
    }

    test("an Age keeps the overworld's time of day") {
        server.run("age compose clockage 7 terrain=hills sea=minecraft:water carvers=caves sky=plain")

        server.run("time set noon")
        val atNoon = clockOf("clockage")
        server.run("time set midnight")
        val atMidnight = clockOf("clockage")

        // Named values rather than "the two differ", so an Age on a clock of its own that happens to tick
        // fails too.
        check(atNoon % 24_000 in (6_000 - slack)..(6_000 + slack)) {
            "At overworld noon the Age's clock read $atNoon, which is not noon — its clock is not the overworld's"
        }
        check(atMidnight % 24_000 in (18_000 - slack)..(18_000 + slack)) {
            "At overworld midnight the Age's clock read $atMidnight, which is not midnight — the Age has no night, " +
                "so it has no stars either"
        }
    }
})

package co.voik.agesandtheart.server

import co.voik.agesandtheart.command.SkyInstruments
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

    /**
     * The number written after [label], **read to the end of its own digits and no further**. RCON hands
     * back a command's messages concatenated with no separator, so the buffer is one line and taking
     * everything after a label swallows whatever the next one says.
     */
    fun numberAfter(report: String, label: String): Long? =
        report.substringAfter("$label ", missingDelimiterValue = "")
            .trimStart()
            .takeWhile(Char::isDigit)
            .toLongOrNull()

    fun clockOf(age: String): Long {
        val report = server.run("age sky $age")
        val reading = numberAfter(report, SkyInstruments.CLOCK_LABEL)
        check(reading != null) { "'/age sky $age' reported no clock, so this check cannot see the thing it guards:\n$report" }
        return reading
    }

    fun litAsOf(age: String): Long? = numberAfter(server.run("age sky $age"), SkyInstruments.LIT_AS_LABEL)

    /**
     * **An Age's clock and the hour it is lit as are two numbers, and only the second decides a colour.**
     *
     * The Spire keeps the overworld's clock like every other Age and has never had a sun, so every one of
     * `OVERWORLD_DAY`'s tracks — the sky-light colour, the sunrise band, the light itself — used to run on
     * the overworld's schedule beneath a sky pinned at midnight. It glowed warm at dusk and went dark at
     * midnight under stars that were always out (Jonah, 2026-08-27, walked). Nothing rises there, so the
     * hour is midnight and stays there.
     */
    test("a sky with no suns is lit as midnight whatever the clock says") {
        server.run("age create spire spirelit")

        server.run("time set noon")
        val atNoon = litAsOf("spirelit")
        server.run("time set midnight")
        val atMidnight = litAsOf("spirelit")

        check(atNoon == 18_000L && atMidnight == 18_000L) {
            "The Spire was lit as $atNoon at noon and $atMidnight at midnight, where a sunless sky has " +
                "only the one hour"
        }
        // The control: its *clock* does still run, so this is a mapping and not a stopped level.
        check(clockOf("spirelit") % 24_000 in (18_000 - slack)..(18_000 + slack)) {
            "The Spire's own clock did not follow the overworld, so the mapping above proves nothing"
        }
    }

    test("an Age keeps the overworld's time of day") {
        server.run("age compose clockage 7 landmass=hills sea=minecraft:water rock=caves")

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

package co.voik.agesandtheart.server

import com.google.gson.JsonParser
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * That `eclipsing moon`, written as a book writes it, reaches the sky the server holds, and that `/age eclipses`
 * reads it back: once a month, total, with the sun overhead. What it looks like is the walk's business.
 */
@Tags(NEEDS_SERVER)
class EclipseCommandCheck : FunSpec({
    val server = DrivenServer.shared

    val eclipsed = "eclipsed"
    val plain = "uneclipsed"

    beforeSpec {
        server.run("age write $eclipsed 11 age eclipsing moon")
        server.run("age compose $plain 5 landmass=gentle sea=minecraft:water")
    }

    fun eclipsesOver(name: String, days: Int) =
        JsonParser.parseString(server.run("age eclipses json $name $days").trim()).asJsonObject

    test("an eclipsing moon covers the sun once a month, wholly, with the sun overhead") {
        val report = eclipsesOver(eclipsed, days = 17)
        val eclipses = report.getAsJsonArray("eclipses")?.map { it.asJsonObject }.orEmpty()

        check(report.get("count")?.asInt == 2) { "Seventeen days held ${report.get("count")} eclipses:\n$report" }
        check(eclipses.all { it.get("totalTicks").asLong > 0 }) { "An eclipse was not total:\n$report" }
        check(eclipses.all { it.get("sunAltitude").asFloat > 80.0f }) { "An eclipse was not at noon:\n$report" }
    }

    test("an Age with one ordinary moon has none") {
        val report = eclipsesOver(plain, days = 32)
        check(report.get("count")?.asInt == 0) { "Vanilla's moon eclipsed the sun:\n$report" }
    }

    test("the prose says how long the dark lasts and whether the sun is up to see it") {
        val report = server.run("age eclipses $eclipsed 17")
        check("total for" in report) { "The prose did not say how long totality lasts:\n$report" }
        check("up" in report) { "The prose did not say the sun was up:\n$report" }
    }
})

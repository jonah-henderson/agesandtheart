package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * A deluge has a sea to raise whatever rock the Age wears: vanilla's pours its own and declares no waterline of
 * ours, and an Age on it rained for days and drowned nothing. The rise itself waits on somebody being there, so
 * what is held here is that the sea is found, at the level the rock pours it.
 */
@Tags(NEEDS_SERVER, NEEDS_TIME)
class DelugeSeaOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    fun seaOf(age: String) = server.ask("instability", age)

    test("a deluge on vanilla's rock raises the sea vanilla pours, from its surface") {
        server.run("age write vanilladeluge 7 age deluge phenomena")
        val report = seaOf("vanilladeluge")
        check(report.has("seaStanding") && !report.get("seaStanding").isJsonNull) { "no sea for the deluge to raise: $report" }
        check(report.get("seaWritten").asInt == VANILLAS_WATER_SURFACE) { "the sea was written at ${report.get("seaWritten")}" }
    }

    test("a deluge on a landform of ours still has its sea") {
        server.run("age write islanddeluge 7 age insular landmass deluge phenomena")
        val report = seaOf("islanddeluge")
        check(report.has("seaStanding") && !report.get("seaStanding").isJsonNull) { "no sea for the deluge to raise: $report" }
    }
})

/** The overworld's sea level is 63, the block over the water; its surface is the top block of water. */
private const val VANILLAS_WATER_SURFACE = 62

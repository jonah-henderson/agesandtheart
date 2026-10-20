package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * A deluge has a sea to raise whatever rock the Age wears: vanilla's pours its own and declares no waterline of
 * ours, and an Age on it rained for days and drowned nothing. The rise itself waits on somebody being there, so
 * what is held here is that the sea is found, at the level the rock pours it.
 */
@Tags(NEEDS_SERVER)
class DelugeSeaOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    fun seaOf(age: String) = server.ask("instability", age)

    test("a deluge on vanilla's rock raises the sea vanilla pours, from its surface") {
        server.run("age write vanilladeluge 7 age deluge phenomena")
        val report = seaOf("vanilladeluge")
        check(report.has("seaStanding") && !report.get("seaStanding").isJsonNull) { "no sea for the deluge to raise: $report" }
        check(report.get("seaWritten").asInt == VANILLAS_WATER_SURFACE) { "the sea was written at ${report.get("seaWritten")}" }
    }

    test("a chunk made in a drowned Age on vanilla's rock comes out with the sea where it stands") {
        server.run("age write vanilladrowned 7 age deluge phenomena")
        server.run("age instability vanilladrowned rained $RAINED_FOR")
        server.sprint(TICKS_FOR_THE_SEA_TO_STAND)
        val report = seaOf("vanilladrowned")
        val standing = report.get("seaStanding").asInt
        check(standing > report.get("seaWritten").asInt) { "the sea has not risen: $report" }
        val found = server.run("execute in agesandtheart:vanilladrowned run locate biome #minecraft:is_ocean")
        val (x, z) = Regex("""\[(-?\d+), [^,]+, (-?\d+)]""").find(found)?.destructured ?: error("no ocean: $found")
        server.run("execute in agesandtheart:vanilladrowned run forceload add $x $z")
        // Nobody is here, so nothing has raised this chunk since it was made: what stands at the line, it was made with.
        val said = server.run("execute in agesandtheart:vanilladrowned run fill $x $standing $z ${x.toInt() + SIDE} $standing ${z.toInt() + SIDE} minecraft:stone replace minecraft:water")
        val atTheLine = Regex("""filled (\d+)""").find(said)?.groupValues?.get(1)?.toInt() ?: 0
        check(atTheLine > 0) { "a fresh chunk over the ocean at $x, $z has no water at y=$standing, where the sea stands" }
    }

    test("a deluge on a landform of ours still has its sea") {
        server.run("age write islanddeluge 7 age insular landmass deluge phenomena")
        val report = seaOf("islanddeluge")
        check(report.has("seaStanding") && !report.get("seaStanding").isJsonNull) { "no sea for the deluge to raise: $report" }
    }
})

/** The overworld's sea level is 63, the block over the water; its surface is the top block of water. */
private const val VANILLAS_WATER_SURFACE = 62

/** Days of rain with somebody there, which carries any deluge as high as it goes. */
private const val RAINED_FOR = 1_000_000

/** The sea is stood where the rain puts it once a tick; a sprint returns once its ticks have run. */
private const val TICKS_FOR_THE_SEA_TO_STAND = 5

private const val SIDE = 15

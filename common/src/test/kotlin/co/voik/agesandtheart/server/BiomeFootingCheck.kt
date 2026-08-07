package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * Whether an Age's biomes agree with the ground it actually has (`Grounding`, design §3.4).
 *
 * **Deliberately not tagged `NEEDS_LANDFORMS`**, though it is about worldgen and its neighbour
 * [GenerationCheck] is. That tag buys a spec out of the build gate and into a task that only warns, and it
 * is earned by *sampling a field and asserting an emergent shape* — millions of columns, minutes of cycle,
 * a reading that moves whenever a landform is retuned. This asks a categorical question of one small
 * census: does a world with no water in it grow ocean. That answer cannot drift with tuning, it costs one
 * Age at radius six, and it is exactly the sort of thing that came back three times because nothing could
 * fail a build over it.
 */
@Tags(NEEDS_SERVER)
class BiomeFootingCheck : FunSpec({
    val server = DrivenServer.shared

    /**
     * **An Age with no sea grows no ocean.** The third face of a complaint that has come back three times
     * with three different causes, and the one a check can actually hold.
     *
     * `sea open` fills the world's empty space with air, and the waterline stays where the shape declared
     * it — a level is a datum, not a thing poured. `Grounding` reads continentalness as how far the ground
     * stands over that line, so an Age with nothing at it filed **every column** as seabed: measured at
     * 100% warm ocean over dry pyramids (Jonah, 2026-08-06, walked).
     *
     * The assertion is "no ocean at all" rather than a share, because there is no honest middle — an ocean
     * biome in a world with no water in it is wrong at any percentage.
     */
    test("an Age with no sea grows no ocean biomes") {
        server.run("age compose sealess 4242 terrain=pyramids sea=minecraft:air sky=plain")
        val oceanic = surfaceBiomes(server, "sealess", radius = 6).filter { "ocean" in it.key }
        check(oceanic.isEmpty()) {
            "an Age with an air sea grew ocean biomes over dry ground: " +
                oceanic.entries.joinToString { "${it.key} ${"%.1f".format(it.value)}%" }
        }
    }

    /** The control: the same axis under water still reaches the sea, or the check above proves nothing. */
    test("an Age that does have a sea still grows ocean over it") {
        server.run("age compose seabound 4242 terrain=islands sea=minecraft:water sky=plain")
        val oceanic = surfaceBiomes(server, "seabound", radius = 6).filter { "ocean" in it.key }
        check(oceanic.isNotEmpty()) {
            "an Age of islands in a water sea grew no ocean biome anywhere, so the reading above is vacuous"
        }
    }
    /**
     * **Nothing may be kept dry and claimed by the aquifer at once.** Those are contradictory claims about
     * the same space, and the contradiction flooded every rift in the mod.
     *
     * A terrain with noise caves hands its *uncut* rock over as the volume the aquifer answers for, since a
     * carved cave meets the water table on its way out. A rift is opened afterwards, at the Age level — so
     * the chasm stayed inside that volume, and the aquifer branch is asked *before* the sea and does not
     * consult what keeps the sea out. `SeaFill.dry` was working perfectly the whole time; the water came
     * through a door it was not guarding (Jonah, 2026-08-06, walked, at 290/216 of a `hills,pillars` rift).
     *
     * Asked of the *generator* rather than of blocks, deliberately. Reading a world back needs a loaded
     * chunk, and a probe where nobody stands reads `void_air` and reports a clean negative — which is how
     * this went uncaught through several rounds of looking straight at it.
     */
    test("no column is both kept dry and answered for by the aquifer") {
        server.run(
            "age compose riftdry 1543517247 terrain=hills,pillars terrain.seam=rift " +
                "sea=minecraft:water carvers=solid sky=plain",
        )
        val contradictions = SAMPLED_COLUMNS.mapNotNull { (x, z) ->
            val probe = server.ask("probe", "riftdry $x $z")
            val overlap = probe.get("dryAndAquifer").asInt
            "($x, $z): $overlap levels, kept dry ${probe.get("keptDry").asString}".takeIf { overlap > 0 }
        }
        check(contradictions.isEmpty()) {
            "the aquifer claims space the sea is kept out of, so it fills it:\n" +
                contradictions.take(4).joinToString("\n")
        }
    }
})

/** Columns across the chasm a rift opens, including the one a walk found flooded. */
private val SAMPLED_COLUMNS = listOf(290 to 216, 288 to 216, 292 to 216, 290 to 210, 290 to 224)

/** What share of an Age's surface each biome covers, as `/age biomes` measures it. */
private fun surfaceBiomes(server: DrivenServer, name: String, radius: Int): Map<String, Double> =
    server.ask("biomes", "$name $radius").getAsJsonArray("biomes").associate { entry ->
        val row = entry.asJsonObject
        row.get("biome").asString to row.get("share").asDouble
    }

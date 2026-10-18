package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * An underground written under vanilla's overworld is built there: `none` takes vanilla's noise caves out, and the
 * great halls hollow the rock where its caves would have been. Sampled away from the origin, where this seed stands
 * a structure. And a rock of two blocks is laid as both.
 */
@Tags(NEEDS_SERVER, NEEDS_TIME)
class VanillaUndergroundOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    /** The open air in a band of rock under [CHUNKS] chunks of the Age composed with [underground]. */
    fun openAirUnder(underground: String): Int {
        val age = "vanillaunder$underground".replace("_", "")
        val composed = server.run("age compose $age 11 landmass=overworld sea=minecraft:water rock=solid underground=$underground")
        check("Created Age" in composed) { "the Age was not composed: $composed" }
        var open = 0
        for (chunkX in 0..<CHUNKS) for (chunkZ in 0..<CHUNKS) {
            val x = AWAY + chunkX * SPACING
            val z = AWAY + chunkZ * SPACING
            server.run("execute in agesandtheart:$age run forceload add $x $z")
            val filled = server.run(
                "execute in agesandtheart:$age run fill $x $BAND_BOTTOM $z ${x + CHUNK - 1} $BAND_TOP ${z + CHUNK - 1} " +
                    "minecraft:glass replace minecraft:air",
            )
            open += FILLED.find(filled)?.groupValues?.get(1)?.toInt() ?: 0
            server.run("execute in agesandtheart:$age run forceload remove $x $z")
        }
        return open
    }

    test("no underground leaves vanilla's rock without its caves, and great halls hollow it") {
        val caves = openAirUnder("noise_caves")
        val none = openAirUnder("none")
        val halls = openAirUnder("great_halls")
        check(none * NONE_AT_MOST < caves) { "with no underground $none blocks were open, against $caves with vanilla's caves" }
        check(halls > caves * HALLS_AT_LEAST) { "the great halls opened $halls blocks, against vanilla's caves' $caves" }
    }

    test("a rock of two blocks on vanilla's overworld is both of them, not only the first") {
        val composed = server.run(
            "age compose vanillamingled 11 landmass=overworld landmass.stone=minecraft:andesite,minecraft:granite " +
                "sea=minecraft:water rock=solid underground=none",
        )
        check("Created Age" in composed) { "the Age was not composed: $composed" }
        fun countOf(block: String): Int {
            val said = server.run(
                "execute in agesandtheart:vanillamingled run fill $AWAY $BAND_BOTTOM $AWAY ${AWAY + CHUNK - 1} $BAND_TOP ${AWAY + CHUNK - 1} " +
                    "minecraft:glass replace minecraft:$block",
            )
            return FILLED.find(said)?.groupValues?.get(1)?.toInt() ?: 0
        }
        server.run("execute in agesandtheart:vanillamingled run forceload add $AWAY $AWAY")
        val andesite = countOf("andesite")
        val granite = countOf("granite")
        check(andesite > SOME_OF_IT && granite > SOME_OF_IT) { "andesite $andesite, granite $granite" }
    }
})

private const val CHUNKS = 4

/** Away from the origin, where a structure stands in this seed, and spaced so one cave system is not counted twice. */
private const val AWAY = 1024
private const val SPACING = 160
private const val CHUNK = 16

/** Deep under any ground, and under the halls' ceiling. */
private const val BAND_BOTTOM = -50
private const val BAND_TOP = 0

/**
 * What is left open with no underground is vanilla's structures and underground features, a geode or a lava lake,
 * which the seed measured at half what its caves open; the halls open twelve times as much.
 */
private const val NONE_AT_MOST = 1.5
private const val HALLS_AT_LEAST = 5

/** A share of one chunk's band any mingled block takes, however the mingling falls. */
private const val SOME_OF_IT = 1000

private val FILLED = Regex("filled (\\d+)")

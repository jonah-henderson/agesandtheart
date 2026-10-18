package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * Rime grows round the tops of the tall ice spikes, on gentle land with no cliffs to grow it — and on spikes a book
 * resized, which are copies of vanilla's that have lost its name.
 */
@Tags(NEEDS_SERVER, NEEDS_TIME)
class IceSpikeRimeOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    fun crystalsIn(age: String): Int {
        var crystals = 0
        for (chunkX in -RADIUS_CHUNKS..RADIUS_CHUNKS) for (chunkZ in -RADIUS_CHUNKS..RADIUS_CHUNKS) {
            val x = chunkX * CHUNK
            val z = chunkZ * CHUNK
            server.run("execute in agesandtheart:$age run forceload add $x $z")
            for (band in BANDS) {
                val filled = server.run(
                    "execute in agesandtheart:$age run fill $x ${band.first} $z ${x + CHUNK - 1} ${band.last} ${z + CHUNK - 1} " +
                        "air replace #agesandtheart:rime_crystals",
                )
                crystals += FILLED.find(filled)?.groupValues?.get(1)?.toInt() ?: 0
            }
            server.run("execute in agesandtheart:$age run forceload remove $x $z")
        }
        return crystals
    }

    fun written(age: String, sizes: String) {
        val written = server.run("age write $age 11 age frozen climate blizzard phenomena only ice_spikes biomes $sizes gentle landmass")
        check("Created Age" in written) { "the Age was not written: $written" }
    }

    test("a frozen, blizzard-swept Age of ice spikes on gentle land grows rime on its tall spikes") {
        written("icespikerime", sizes = "")
        val crystals = crystalsIn("icespikerime")
        check(crystals * CHUNKS_PER_CRYSTAL >= CHUNKS) { "$crystals crystals grew in $CHUNKS chunks of ice spikes" }
    }

    test("and on its spikes when a book resized its features") {
        written("smallspikerime", sizes = "minuscule features")
        val crystals = crystalsIn("smallspikerime")
        check(crystals > 0) { "no crystal grew in $CHUNKS chunks of minuscule ice spikes" }
    }
})

private const val RADIUS_CHUNKS = 6
private const val CHUNKS = (2 * RADIUS_CHUNKS + 1) * (2 * RADIUS_CHUNKS + 1)
private const val CHUNK = 16

/** Two fills, each the most one command may change, from the sea floor up. */
private val BANDS = listOf(32..159, 160..287)

/** At least one crystal in so many chunks of spikes; the seed measured one in fifteen, a tall spike being one in sixty. */
private const val CHUNKS_PER_CRYSTAL = 30

private val FILLED = Regex("filled (\\d+)")

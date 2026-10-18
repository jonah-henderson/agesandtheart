package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * Rime grows round the tops of ice spikes, where a frozen Age with no cliffs would otherwise grow none: every crystal
 * counted here stands below the height a cliff's can.
 */
@Tags(NEEDS_SERVER, NEEDS_TIME)
class IceSpikeRimeOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    fun inAge(command: String) = server.run("execute in agesandtheart:icespikerime run $command")

    test("a frozen, blizzard-swept Age of ice spikes grows rime on its spikes, below where any cliff grows it") {
        val written = server.run("age write icespikerime 11 age frozen climate blizzard phenomena only ice_spikes biomes overworld landmass")
        check("Created Age" in written) { "the Age was not written: $written" }
        var crystals = 0
        for (chunkX in -RADIUS_CHUNKS..RADIUS_CHUNKS) for (chunkZ in -RADIUS_CHUNKS..RADIUS_CHUNKS) {
            val x = chunkX * CHUNK
            val z = chunkZ * CHUNK
            inAge("forceload add $x $z")
            val filled = inAge("fill $x $LOWEST $z ${x + CHUNK - 1} $BELOW_ANY_CLIFFS ${z + CHUNK - 1} air replace #agesandtheart:rime_crystals")
            crystals += FILLED.find(filled)?.groupValues?.get(1)?.toInt() ?: 0
            inAge("forceload remove $x $z")
        }
        val chunks = (2 * RADIUS_CHUNKS + 1) * (2 * RADIUS_CHUNKS + 1)
        check(crystals * CHUNKS_PER_CRYSTAL >= chunks) { "$crystals crystals grew below y=$BELOW_ANY_CLIFFS in $chunks chunks of ice spikes" }
    }
})

private const val RADIUS_CHUNKS = 3
private const val CHUNK = 16
private const val LOWEST = 32

/** At least one crystal in so many chunks of spikes; the seed measured one and a half to a chunk. */
private const val CHUNKS_PER_CRYSTAL = 2

/** One under `SheerFace.NOTHING_BELOW`, where no cliff grows a crystal. */
private const val BELOW_ANY_CLIFFS = 95

private val FILLED = Regex("filled (\\d+)")

package co.voik.agesandtheart.optics

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel

/**
 * Light across optical conduits: everything joined to one network of them shares, at once, the light put into it.
 *
 * Collectors and receivers a beam is reaching put light in; emitters and dispersion lenses fed at their back and
 * receivers no beam is reaching take it out, each an equal share of all of it, whether or not it uses its share.
 */
object LightNetworks {
    /** Past this many conduits a network is not followed further. */
    private const val MOST_CONDUITS = 4096

    /** One network: its conduits, and what joins them. */
    data class Network(val conduits: Set<BlockPos>, val endpoints: Set<BlockPos>)

    /** The light reaching the output at [output] from the network it is joined to. */
    fun lightFor(level: ServerLevel, output: BlockPos): Light? = networkAt(level, output)?.let { shareOf(level, it) }

    /** The network joined to the endpoint at [endpoint] on its one joining side, or null where none is. */
    private fun networkAt(level: ServerLevel, endpoint: BlockPos): Network? {
        val side = OpticalConduitBlock.joiningSide(level.getBlockState(endpoint)) ?: return null
        val conduit = endpoint.relative(side)
        return if (level.getBlockState(conduit).block is OpticalConduitBlock) networkFrom(level, conduit) else null
    }

    private fun networkFrom(level: ServerLevel, start: BlockPos): Network {
        val conduits = mutableSetOf(start)
        val endpoints = mutableSetOf<BlockPos>()
        val waiting = ArrayDeque(listOf(start))
        while (waiting.isNotEmpty() && conduits.size < MOST_CONDUITS) {
            val at = waiting.removeFirst()
            val state = level.getBlockState(at)
            for (direction in Direction.entries) {
                val next = at.relative(direction)
                if (next in conduits || !level.isLoaded(next)) continue
                val nextState = level.getBlockState(next)
                when {
                    !OpticalConduitBlock.joins(state, direction, nextState) -> {}
                    nextState.block is OpticalConduitBlock -> {
                        conduits += next
                        waiting += next
                    }
                    else -> endpoints += next
                }
            }
        }
        return Network(conduits, endpoints)
    }

    /** What each output of [network] takes: all the light put in, shared equally between them. */
    private fun shareOf(level: ServerLevel, network: Network): Light? {
        val putIn = network.endpoints.mapNotNull { endpoint -> lightPutIn(level, endpoint) }
        val outputs = network.endpoints.count { endpoint -> takesLightOut(level, endpoint) }
        if (outputs == 0) return null
        return Light.together(putIn)?.sharedBetween(outputs)
    }

    private fun lightPutIn(level: ServerLevel, endpoint: BlockPos): Light? = when (val entity = level.getBlockEntity(endpoint)) {
        is SunlightCollectorBlockEntity -> entity.gathered(level)
        is BeamReceiverBlockEntity -> entity.beamLight(level)
        else -> null
    }

    private fun takesLightOut(level: ServerLevel, endpoint: BlockPos): Boolean = when (val entity = level.getBlockEntity(endpoint)) {
        is AstriteEmitterBlockEntity -> true
        is DispersionLensBlockEntity -> true
        is BeamReceiverBlockEntity -> entity.beamLight(level) == null
        else -> false
    }
}

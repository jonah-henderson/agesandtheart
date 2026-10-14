package co.voik.agesandtheart.worldgen

import net.minecraft.world.level.levelgen.Aquifer
import net.minecraft.world.level.levelgen.Beardifier
import net.minecraft.world.level.levelgen.NoiseChunk
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings
import net.minecraft.world.level.levelgen.RandomState
import net.minecraft.world.level.levelgen.blending.Blender
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext

/**
 * A [NoiseChunk] whose final density is read, in the columns a [Dislocation] moves, from the same router
 * a long way off. Only the final density is moved: the aquifer, the surface and the biomes keep reading
 * the place they stand, which is what makes a patch foreign rather than merely relocated.
 */
class DislocatedNoiseChunk(
    randomState: RandomState,
    beardifier: Beardifier?,
    private val settings: NoiseGeneratorSettings,
    globalFluidPicker: Aquifer.FluidPicker,
    blender: Blender,
    volume: DensityVolume,
    private val dislocation: Dislocation,
) : NoiseChunk(randomState, beardifier, settings, globalFluidPicker, blender, volume) {

    private val finalDensity = settings.noiseRouter().finalDensity()

    // Uncached and with no structures in it: nothing here may be read at the place the column stands.
    private val shiftedContext = SamplerContext.builder().build()

    private val moved = DensitySamplerSet { function ->
        val bound = super.cachingSamplers().get(function)
        if (function !== finalDensity) bound else DislocatingSampler(bound.sampler()).bind(bound.context())
    }

    override fun cachingSamplers(): DensitySamplerSet = moved

    private inner class DislocatingSampler(private val inner: DensitySampler) : DensitySampler {

        override fun sampleValue(context: SamplerContext, blockX: Int, blockY: Int, blockZ: Int): Float {
            val shift = dislocation.shiftAt(blockX, blockZ)
            return if (shift.isNone) inner.sampleValue(context, blockX, blockY, blockZ)
            else inner.sampleValue(shiftedContext, blockX + shift.x, blockY, blockZ + shift.z)
        }

        override fun sampleVolume(context: SamplerContext, outputBuffer: DensityBuffer, volume: DensityVolume) {
            inner.sampleVolume(context, outputBuffer, volume)
            columnsByShift(volume).forEach { (shift, columns) ->
                val movedVolume = DensityVolume(
                    volume.sizeX(), volume.sizeY(), volume.sizeZ(),
                    volume.minBlockX() + shift.x, volume.minBlockY(), volume.minBlockZ() + shift.z,
                    volume.stepBlockX(), volume.stepBlockY(), volume.stepBlockZ(),
                )
                shiftedContext.acquireBuffer(movedVolume).use { elsewhere ->
                    inner.sampleVolume(shiftedContext, elsewhere, movedVolume)
                    for ((indexX, indexZ) in columns) {
                        for (indexY in 0..<volume.sizeY()) {
                            val index = volume.indexUnchecked(indexX, indexY, indexZ)
                            outputBuffer.set(index, elsewhere.get(index))
                        }
                    }
                }
            }
        }

        /** The volume's columns that are read from elsewhere, grouped by where, as (indexX, indexZ). */
        private fun columnsByShift(volume: DensityVolume): Map<Dislocation.Shift, List<Pair<Int, Int>>> {
            val grouped = HashMap<Dislocation.Shift, MutableList<Pair<Int, Int>>>()
            for (indexZ in 0..<volume.sizeZ()) {
                for (indexX in 0..<volume.sizeX()) {
                    val shift = dislocation.shiftAt(volume.blockX(indexX), volume.blockZ(indexZ))
                    if (!shift.isNone) grouped.getOrPut(shift) { mutableListOf() }.add(indexX to indexZ)
                }
            }
            return grouped
        }
    }
}

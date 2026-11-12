package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.worldgen.Perforation
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.feature.Feature

/**
 * A feature holed through wherever a [Perforation] of [porosity] falls — `porous trees`, `porous obelisks`. Everything
 * it lays may be a hole, wood and leaves as well as rock, since the book named it; but never what holds something.
 */
data class PerforatedFeature(val feature: Holder<Feature>, val porosity: Double, val seed: Long) : Feature {

    private val holes: Perforation by lazy { Perforation(seed, HOLE_SIZE, porosity) }

    override fun codec(): MapCodec<out Feature> = CODEC

    override fun place(level: WorldGenLevel, generator: ChunkGenerator, random: RandomSource, origin: BlockPos): Boolean =
        feature.value().place(SkippingLevel.of(level, ::isHole), generator, random, origin)

    private fun isHole(at: BlockPos, state: BlockState): Boolean {
        val isSolidAndHoldsNothing = !state.isAir && state.fluidState.isEmpty && !state.hasBlockEntity()
        return isSolidAndHoldsNothing && holes.isHoleAt(at.x, at.y, at.z)
    }

    companion object {
        /** The scale that makes a hole through a feature about two blocks across ([Perforation]). */
        const val HOLE_SIZE = 2.5

        val CODEC: MapCodec<PerforatedFeature> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Feature.CODEC.fieldOf("feature").forGetter(PerforatedFeature::feature),
                Codec.DOUBLE.fieldOf("porosity").forGetter(PerforatedFeature::porosity),
                Codec.LONG.fieldOf("seed").forGetter(PerforatedFeature::seed),
            ).apply(instance, ::PerforatedFeature)
        }
    }
}

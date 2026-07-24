package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

/**
 * The background a shape sits in — orthogonal to the shape itself. Everything below [level] that the
 * shape leaves empty is filled with [block] (a sea of water, plasma, lava, …). [VOID] fills nothing.
 */
data class AmbientMedium(val block: BlockState, val level: Int) {

    fun fillsAt(y: Int): Boolean = y < level && !block.isAir

    companion object {
        val CODEC: MapCodec<AmbientMedium> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                BlockState.CODEC.fieldOf("block").forGetter(AmbientMedium::block),
                Codec.INT.fieldOf("level").forGetter(AmbientMedium::level),
            ).apply(instance, ::AmbientMedium)
        }

        /** Empty space all the way down. */
        val VOID = AmbientMedium(Blocks.AIR.defaultBlockState(), Int.MIN_VALUE)

        /** A fluid sea filling everything below [level]. */
        fun sea(block: BlockState, level: Int) = AmbientMedium(block, level)
    }
}

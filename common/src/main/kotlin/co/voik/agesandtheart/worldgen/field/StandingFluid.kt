package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.world.level.block.state.BlockState

/**
 * A body of fluid the shape carries, **with a substance of its own** — a caldera's lava lake, where
 * [SeaFill.wet] is a river.
 *
 * The two are the same idea at different ends of one question. A river is water at whatever the sea is
 * made of here, so it needs no substance and rides on [SeaFill.wet]; a lava lake on a mountaintop in an
 * Age whose sea is water needs to say what it is, and nothing about the waterline can answer that.
 *
 * **It fills at generation and never moves**, which is the whole reason it exists: a lake laid by the
 * shape is flat, complete on arrival and costs nothing per tick, where anything filled at runtime has to
 * work out where fluid may stand — and vanilla's own fluid is the only thing that knows.
 */
data class StandingFluid(val where: TerrainField, val fluid: BlockState) {
    companion object {
        fun codec(self: Codec<TerrainField>): MapCodec<StandingFluid> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                self.fieldOf("where").forGetter(StandingFluid::where),
                BlockState.CODEC.fieldOf("fluid").forGetter(StandingFluid::fluid),
            ).apply(instance, ::StandingFluid)
        }
    }
}

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
data class StandingFluid(
    val where: TerrainField,
    val fluid: BlockState,
    /**
     * Which body this is, for a feature that must find *its own* — a vent seated in a crater lake and one
     * seated in a magma chamber are different features asking the same question.
     *
     * The substance cannot answer it: both are lava, so a search for the first lava body finds whichever
     * happens to be listed first and the second is unreachable. Nothing else may be used instead — order
     * is not a name, and rebuilding the field from the seed gives a second field free to disagree with the
     * one the rock was cut from ([co.voik.agesandtheart.worldgen.VolcanoField]).
     */
    val named: String? = null,
) {
    companion object {
        /** The crater lakes a volcano's cones arrive full of. */
        const val CRATER_LAKES = "crater_lakes"

        /** The pools standing in the bottom of the magma chambers. */
        const val CHAMBER_POOLS = "chamber_pools"

        fun codec(self: Codec<TerrainField>): MapCodec<StandingFluid> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                self.fieldOf("where").forGetter(StandingFluid::where),
                BlockState.CODEC.fieldOf("fluid").forGetter(StandingFluid::fluid),
                Codec.STRING.optionalFieldOf("named").forGetter { java.util.Optional.ofNullable(it.named) },
            ).apply(instance) { where, fluid, named -> StandingFluid(where, fluid, named.orElse(null)) }
        }
    }
}

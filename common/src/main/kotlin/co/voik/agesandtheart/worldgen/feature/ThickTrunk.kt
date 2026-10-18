package co.voik.agesandtheart.worldgen.feature

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.TreeFeature

/**
 * A tree grown with a trunk [girth] logs across: grown as it would be into a [FeatureDraft], and every log it laid
 * widened into a square of logs, its branches with it. Vanilla's thickest trunk is two across and its trunk placers
 * cannot be added to without widening a private constructor, so the thickening is done to what the tree laid.
 *
 * A widened log goes only where a tree may grow ([TreeFeature.validTreePos]), so it never cuts into the hillside,
 * and the columns beside the base reach down to the ground so a thick trunk does not stand on air. A crown grown too
 * wide for its trunk to hold is given [Branches].
 */
data class ThickTrunk(val tree: Holder<Feature>, val girth: Int) : Feature {

    override fun codec(): MapCodec<out Feature> = CODEC

    override fun place(level: WorldGenLevel, generator: ChunkGenerator, random: RandomSource, origin: BlockPos): Boolean {
        val draft = FeatureDraft(level)
        if (!tree.value().place(draft.level, generator, random, origin)) return false
        val grown = draft.laid.toMutableMap()
        fun mayGrowAt(at: BlockPos): Boolean {
            val standing = grown[at] ?: return draft.canWrite(at) && TreeFeature.validTreePos(draft.real, at)
            return draft.canWrite(at) && !Branches.isLog(standing)
        }
        val logs = draft.laid.filterValues(Branches::isLog)
        val base = logs.keys.minOfOrNull { it.y }
        for ((at, log) in logs) {
            for (beside in squareAround(at)) {
                if (!mayGrowAt(beside)) continue
                grown[beside] = log
                if (at.y == base) reachDown(grown, beside, log, ::mayGrowAt)
            }
        }
        Branches.grownInto(grown, ::mayGrowAt)
        for ((at, state) in grown) {
            if (draft.canWrite(at)) level.setBlock(at, state, Block.UPDATE_CLIENTS)
        }
        return true
    }

    /** The other columns of the [girth]-wide square around [at]: centred on it where the girth is odd. */
    private fun squareAround(at: BlockPos): List<BlockPos> {
        val from = -(girth - 1) / 2
        return (from..<from + girth).flatMap { dx -> (from..<from + girth).map { dz -> at.offset(dx, 0, dz) } }
            .filter { it != at }
    }

    private fun reachDown(grown: MutableMap<BlockPos, BlockState>, from: BlockPos, log: BlockState, mayGrowAt: (BlockPos) -> Boolean) {
        var below = from.below()
        repeat(MOST_REACHED_DOWN) {
            if (!mayGrowAt(below)) return
            grown[below] = log
            below = below.below()
        }
    }

    companion object {
        val CODEC: MapCodec<ThickTrunk> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Feature.CODEC.fieldOf("tree").forGetter(ThickTrunk::tree),
                Codec.INT.fieldOf("girth").forGetter(ThickTrunk::girth),
            ).apply(instance, ::ThickTrunk)
        }

        /** How far a column beside the base reaches down looking for the ground. */
        private const val MOST_REACHED_DOWN = 6
    }
}

package co.voik.agesandtheart.worldgen.feature

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.BlockTags
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.feature.Feature

/**
 * What grew in a pit, taken out again: trees, bushes and plants over the ground a [pit] dug, so a pit reads
 * as bare mud and sand and a colony in one is found at a glance (Jonah, 2026-09-30).
 *
 * **A second pass, after the vegetation.** A pit is dug before anything grows, so that nothing is left
 * standing on ground it cut away — and so the trees then grow in it, on its mud. This runs where the snow is
 * laid, works out the same pits the dig did, and clears their columns from the floor up.
 *
 * A crown reaching out over the rim from a trunk that stood in the pit is not cut off at the rim: the leaves
 * beside every log taken are marked for the recount vanilla gives a chunk as it loads, so they find no log
 * and fall over the next few minutes as a felled tree's do.
 */
data class PitClearing(val pit: Formation) : Feature {

    override fun codec(): MapCodec<out Feature> = CODEC

    override fun place(
        level: WorldGenLevel,
        generator: ChunkGenerator,
        random: RandomSource,
        origin: BlockPos,
    ): Boolean {
        val floors = mutableMapOf<Long, BlockPos>()
        pit.raiseIn(level, generator, origin, clear = {}) { at, _ ->
            val column = BlockPos.asLong(at.x, 0, at.z)
            if ((floors[column]?.y ?: Int.MIN_VALUE) < at.y) floors[column] = at
        }
        var cleared = 0
        for (floor in floors.values) cleared += clearOver(level, floor)
        return cleared > 0
    }

    private fun clearOver(level: WorldGenLevel, floor: BlockPos): Int {
        var cleared = 0
        val at = floor.mutable().move(Direction.UP)
        while (at.y <= level.maxY) {
            val state = level.getBlockState(at)
            if (isGrowth(state)) {
                if (state.`is`(BlockTags.LOGS)) letTheLeavesGo(level, at)
                level.setBlock(at, AIR, PLACED_BY_WORLDGEN)
                cleared++
            }
            at.move(Direction.UP)
        }
        return cleared
    }

    /** The leaves beside a log about to go, marked to recount their distance from a log when they load. */
    private fun letTheLeavesGo(level: WorldGenLevel, log: BlockPos) {
        for (side in Direction.entries) {
            val beside = log.relative(side)
            if (level.getBlockState(beside).`is`(BlockTags.LEAVES)) level.getChunk(beside).markPosForPostProcessing(beside)
        }
    }

    /** A tree, a bush or a plant — never the ground, a structure or the water. */
    private fun isGrowth(state: BlockState): Boolean {
        val isATree = state.`is`(BlockTags.LOGS) || state.`is`(BlockTags.LEAVES)
        val isAPlant = state.`is`(BlockTags.REPLACEABLE_BY_TREES) && state.fluidState.isEmpty
        val isGrownOnTheGround = state.`is`(BlockTags.SAPLINGS) || state.`is`(BlockTags.FLOWERS) || state.block in CROPS_OF_THE_WILD
        return isATree || isAPlant || isGrownOnTheGround
    }

    companion object {
        val CODEC: MapCodec<PitClearing> = Formation.CODEC.fieldOf("pit").xmap(::PitClearing, PitClearing::pit)

        private val CROPS_OF_THE_WILD = setOf(
            Blocks.BAMBOO, Blocks.BAMBOO_SAPLING, Blocks.COCOA, Blocks.SUGAR_CANE, Blocks.CACTUS,
            Blocks.MELON, Blocks.PUMPKIN, Blocks.SWEET_BERRY_BUSH,
            Blocks.BROWN_MUSHROOM_BLOCK, Blocks.RED_MUSHROOM_BLOCK, Blocks.MUSHROOM_STEM,
        )

        private val AIR: BlockState = Blocks.AIR.defaultBlockState()

        /** Vanilla's own flag for a block a feature lays: change it, and do not tell a neighbour. */
        private const val PLACED_BY_WORLDGEN = 2
    }
}

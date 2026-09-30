package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties

/**
 * Putting a [PaperTreeShape] into the world — the one routine a generated grove, a sapling and a root
 * growing back all end in, so a tree is the same thing however it came to stand.
 */
object PaperTreeGrowth {

    /**
     * The whole tree at [heart], or false where its trunk has no room, in which case nothing is placed.
     *
     * The heart replaces the ground it stands in; the trunk and its branches must each find open air, or
     * leaves or plants to push aside; leaves go only where there is room; roots go into open air, water or
     * soft ground, and stop at rock.
     */
    fun grow(level: WorldGenLevel, heart: BlockPos, seed: Long, moisture: Int, flags: Int): Boolean {
        val shape = PaperTreeShape.grownFrom(heart, seed)
        if (!shape.logs.all { isRoomFor(level, it.at) }) return false
        val root = AgeContent.PAPER_TREE_ROOT_BLOCK.defaultBlockState().setValue(PaperTreeHealth.MOISTURE, moisture)
        level.setBlock(heart, root, flags)
        (level.getBlockEntity(heart) as? PaperTreeRootBlockEntity)?.plant(seed, PaperTreeHealth.historyFor(moisture))
        for (log in shape.logs) level.setBlock(log.at, livingLog(log.axis), flags)
        for ((at, distance) in shape.leaves) {
            if (isRoomFor(level, at)) level.setBlock(at, livingLeaf(distance), flags)
        }
        for (at in shape.roots) rootInto(level, at, flags)
        return true
    }

    fun livingLog(axis: Direction.Axis): BlockState =
        AgeContent.PAPER_TREE_LOG_BLOCK.defaultBlockState()
            .setValue(RotatedPillarBlock.AXIS, axis)
            .setValue(PaperTreeLogBlock.OF_THE_TREE, true)

    fun livingLeaf(distance: Int): BlockState =
        AgeContent.PAPER_TREE_LEAVES_BLOCK.defaultBlockState()
            .setValue(LeavesBlock.DISTANCE, distance)
            .setValue(LeavesBlock.PERSISTENT, false)

    /** Whether the tree may grow into [at]: open air, or something growing there it would push aside. */
    fun isRoomFor(level: WorldGenLevel, at: BlockPos): Boolean {
        val state = level.getBlockState(at)
        val isGrowth = state.`is`(BlockTags.LEAVES) || state.`is`(BlockTags.REPLACEABLE_BY_TREES)
        return state.isAir || isGrowth
    }

    /** One root, waterlogged where it grows into water, and not at all into anything harder than earth. */
    private fun rootInto(level: WorldGenLevel, at: BlockPos, flags: Int) {
        val state = level.getBlockState(at)
        val isWater = state.`is`(Blocks.WATER)
        if (!isRoomFor(level, at) && !isWater && !isSoftGround(state)) return
        val waterlogged = isWater && state.fluidState.isSource
        val root = AgeContent.PAPER_TREE_ROOTS_BLOCK.defaultBlockState()
            .setValue(BlockStateProperties.WATERLOGGED, waterlogged)
        level.setBlock(at, root, flags)
    }

    /** What a root pushes through: earth, sand, mud and the like, never stone. */
    private fun isSoftGround(state: BlockState): Boolean {
        val isEarth = state.`is`(BlockTags.DIRT) || state.`is`(BlockTags.SAND)
        val isLoose = state.`is`(Blocks.MUD) || state.`is`(Blocks.GRAVEL) || state.`is`(Blocks.CLAY)
        return isEarth || isLoose
    }
}

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
     * soft ground, and a root stops where it meets rock, so none is laid beyond it cut off from the tree.
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
        for (run in shape.rootRuns) run.takeWhile { at -> rootInto(level, at, flags) }
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

    /**
     * One root, waterlogged where it grows into water, and not at all into anything harder than earth —
     * false there, where the root stops.
     */
    private fun rootInto(level: WorldGenLevel, at: BlockPos, flags: Int): Boolean {
        val state = level.getBlockState(at)
        val isWater = state.`is`(Blocks.WATER)
        if (!isRoomFor(level, at) && !isWater && !isSoftGround(state)) return false
        val waterlogged = isWater && state.fluidState.isSource
        val root = AgeContent.PAPER_TREE_ROOTS_BLOCK.defaultBlockState()
            .setValue(BlockStateProperties.WATERLOGGED, waterlogged)
        level.setBlock(at, root, flags)
        return true
    }

    /**
     * What a root pushes through and a sapling stands in: earth, grass, mud, moss, sand, gravel and clay,
     * never stone. **`#substrate_overworld`, not `#dirt`**: 26.3 took the grass out of `#dirt`, and a sapling
     * that could not be planted on grass was how that showed.
     */
    fun isSoftGround(state: BlockState): Boolean {
        val isEarth = state.`is`(BlockTags.SUBSTRATE_OVERWORLD) || state.`is`(BlockTags.SAND)
        val isLoose = state.`is`(Blocks.GRAVEL) || state.`is`(Blocks.CLAY)
        return isEarth || isLoose
    }
}

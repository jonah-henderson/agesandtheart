package co.voik.agesandtheart.worldgen.feature

import net.minecraft.core.BlockPos
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

/**
 * Ground laid on top of ground, joined to what it lands on (design §7.1.2's craters and puddles alike).
 *
 * **Anything that raises a column by a course owes two writes, not one**, and the second is the one every
 * feature that has tried this forgot: grass with a block on top of it is *dirt*, and vanilla only gets
 * there on a random tick that worldgen never waits for. Miss it and a rim or a scatter of ejecta stands on
 * two courses of grass, one of them buried, until something happens to notice — which in practice is never.
 *
 * **It lays the column's own surface block rather than a block chosen elsewhere.** That is what makes it
 * join: grass meets grass and stone meets stone, in a pack whose Ages are made of whatever the writer
 * said, with nothing here needing to know which. A block taken from the middle of a feature and scattered
 * outward is how a ring of the wrong material ends up round a hole in something else.
 */
object LaidGround {

    /**
     * Raise [ground] by one course of itself, or answer false where the column will not take one.
     *
     * Refuses a column whose top is not solid — nothing should be stacked on a plant or a fluid — and one
     * with anything already in the space above, so this can be asked of every column in a footprint
     * without the caller checking first.
     */
    fun layOn(level: WorldGenLevel, ground: BlockPos, flags: Int): Boolean {
        val surface = level.getBlockState(ground)
        if (!surface.isSolidRender) return false
        val onto = ground.above()
        if (!level.getBlockState(onto).isAir) return false
        level.setBlock(onto, surface, flags)
        BURIED[surface.block]?.let { level.setBlock(ground, it, flags) }
        return true
    }

    /** What a surface block becomes once something is standing on it. */
    private val BURIED: Map<Block, BlockState> = mapOf(
        Blocks.GRASS_BLOCK to Blocks.DIRT.defaultBlockState(),
        Blocks.PODZOL to Blocks.DIRT.defaultBlockState(),
        Blocks.MYCELIUM to Blocks.DIRT.defaultBlockState(),
    )
}

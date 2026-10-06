package co.voik.agesandtheart.content

import co.voik.agesandtheart.worldgen.feature.PalmTree
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * A coconut set down anywhere, and the palm's seed. **Buried**, under ground a palm takes root in and shut in
 * on every other side, it grows a palm on the ground over it in about the time a sapling takes, and that
 * ground fills the coconut's place.
 */
class CoconutBlock(properties: Properties) : Block(properties) {

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    override fun randomTick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        val treeAt = pos.above(2)
        val lightEnough = level.getMaxLocalRawBrightness(treeAt.above()) >= LIGHT_A_SAPLING_NEEDS
        if (random.nextInt(SPROUTS_ONE_TICK_IN) == 0 && lightEnough && isBuried(level, pos)) sprout(level, pos, treeAt, random)
    }

    private fun isBuried(level: BlockGetter, pos: BlockPos): Boolean {
        fun shutInFrom(side: Direction) = level.getBlockState(pos.relative(side)).isFaceSturdy(level, pos.relative(side), side.opposite)
        val coveredByGround = PalmTree.takesRootIn(level.getBlockState(pos.above()))
        val shutInEverywhereElse = Direction.entries.filter { it != Direction.UP }.all(::shutInFrom)
        return coveredByGround && shutInEverywhereElse
    }

    /** The palm checks its own room and footing, as it does from a sapling; if it cannot stand, nothing changes. */
    private fun sprout(level: ServerLevel, pos: BlockPos, treeAt: BlockPos, random: RandomSource) {
        val palm = level.registryAccess().lookupOrThrow(Registries.FEATURE).getValue(PalmWood.TREE) ?: return
        val ground = level.getBlockState(pos.above())
        if (palm.place(level, level.chunkSource.generator, random, treeAt)) level.setBlock(pos, ground, UPDATE_ALL)
    }

    private companion object {
        val SHAPE: VoxelShape = column(10.0, 0.0, 10.0)

        const val LIGHT_A_SAPLING_NEEDS = 9

        /** A sapling's two stages at one random tick in seven each. */
        const val SPROUTS_ONE_TICK_IN = 14
    }
}

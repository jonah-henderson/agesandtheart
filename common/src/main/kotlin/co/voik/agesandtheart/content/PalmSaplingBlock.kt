package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.SaplingBlock
import net.minecraft.world.level.block.grower.TreeGrower
import net.minecraft.world.level.block.state.BlockState

/**
 * A palm's sapling, which **takes root in sand** as well as wherever any sapling does — a palm grows on a
 * beach, and a sapling that refused the beach it fell on would be no use to anyone carrying one home.
 */
class PalmSaplingBlock(grower: TreeGrower, properties: Properties) : SaplingBlock(grower, properties) {

    override fun mayPlaceOn(state: BlockState, level: BlockGetter, pos: BlockPos): Boolean =
        state.`is`(BlockTags.SAND) || super.mayPlaceOn(state, level, pos)
}

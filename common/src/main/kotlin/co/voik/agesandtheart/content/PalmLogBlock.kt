package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.CocoaBlock
import net.minecraft.world.level.block.state.BlockState

/** A palm's log, which grows a new coconut now and then where the tree hangs them: just under the crown. */
class PalmLogBlock(properties: Properties, strippedInto: () -> Block) : StrippableLogBlock(properties, strippedInto) {

    override fun randomTick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        if (random.nextInt(GROWS_A_COCONUT_ONE_TICK_IN) != 0 || !isUnderTheCrown(level, pos)) return
        val side = Direction.Plane.HORIZONTAL.getRandomDirection(random)
        val at = pos.relative(side)
        if (!level.isEmptyBlock(at)) return
        level.setBlock(at, PalmWood.HANGING_COCONUT.defaultBlockState().setValue(CocoaBlock.FACING, side.opposite), UPDATE_ALL)
    }

    companion object {
        /** The log second from the top: a log above it, and the crown's tuft above that. */
        fun isUnderTheCrown(level: BlockGetter, pos: BlockPos): Boolean {
            val trunkGoesOn = level.getBlockState(pos.above()).`is`(PalmWood.LOG)
            val crownOverThat = level.getBlockState(pos.above(2)).`is`(PalmWood.FRONDS)
            return trunkGoesOn && crownOverThat
        }

        /** About once in twenty-five minutes of random ticks at the default tick speed. */
        private const val GROWS_A_COCONUT_ONE_TICK_IN = 22
    }
}

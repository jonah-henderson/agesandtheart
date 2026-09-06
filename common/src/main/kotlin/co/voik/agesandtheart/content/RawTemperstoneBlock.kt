package co.voik.agesandtheart.content

import co.voik.agesandtheart.worldgen.feature.TemperedGround
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState

/**
 * Untempered temperstone, which bakes where it is left near lava.
 *
 * The outermost band of a natural formation and the form that turns up in blobs elsewhere, so the rule a
 * player reads off the ground around lava is also one they can carry away and use: move it closer and it
 * tempers, too close and it spoils.
 */
class RawTemperstoneBlock(properties: BlockBehaviour.Properties) : Block(properties) {

    override fun randomTick(state: BlockState, level: ServerLevel, at: BlockPos, random: RandomSource) {
        val baked = TemperedGround.bakedAt(level, at) ?: return
        level.setBlockAndUpdate(at, baked)
    }
}

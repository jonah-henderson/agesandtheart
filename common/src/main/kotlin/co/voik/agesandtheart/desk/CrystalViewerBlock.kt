package co.voik.agesandtheart.desk

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/** Shows the Age being written at a desk in the room as its linking panel will, before the bind (design §7.4). */
class CrystalViewerBlock(properties: BlockBehaviour.Properties) : Block(properties) {

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hit: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        (player as? ServerPlayer)?.openMenu(CrystalViewerMenuProvider(player, pos))
        return InteractionResult.CONSUME
    }

    companion object {
        /** Whether a viewer stands within [radius] of [pos] — the desk's room, searched as the desk searches it. */
        fun standsNear(level: BlockGetter, pos: BlockPos, radius: Int): Boolean {
            val cursor = BlockPos.MutableBlockPos()
            for (x in -radius..radius) for (y in -radius..radius) for (z in -radius..radius) {
                cursor.setWithOffset(pos, x, y, z)
                if (level.getBlockState(cursor).block is CrystalViewerBlock) return true
            }
            return false
        }
    }
}

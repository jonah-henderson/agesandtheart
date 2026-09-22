package co.voik.agesandtheart.desk

import net.minecraft.util.Prediction
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/**
 * An archive: every page filed in it, without limit, and the supply a writer's desk in the room draws on.
 *
 * **It is storage and not an implement** — it grants no capability and counts toward no tier, for the
 * toolbox's reason (design §7.4). Its pages are read by any desk within the implement survey's radius.
 */
class ArchiveBlock(properties: Properties) : BaseEntityBlock(properties) {


    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = ArchiveBlockEntity(pos, state)

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val serverPlayer = player as? ServerPlayer ?: return InteractionResult.FAIL
        val archive = level.getBlockEntity(pos) as? ArchiveBlockEntity ?: return InteractionResult.FAIL
        ArchiveMenu.open(serverPlayer, archive)
        return InteractionResult.CONSUME
    }

    /** A page or a notebook used on the archive is filed, the same door the screen's shift-click uses. */
    override fun useItemOn(
        itemStack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (!ArchiveIntake.accepts(itemStack)) return InteractionResult.TRY_WITH_EMPTY_HAND
        if (level.isClientSide) return InteractionResult.SUCCESS
        val archive = level.getBlockEntity(pos) as? ArchiveBlockEntity ?: return InteractionResult.TRY_WITH_EMPTY_HAND
        val result = ArchiveIntake.offer(archive, itemStack)
        if (!result.took) return InteractionResult.TRY_WITH_EMPTY_HAND
        player.setItemInHand(hand, result.remainder)
        if (!result.returned.isEmpty) player.inventory.placeItemBackInInventory(result.returned, Prediction.SERVER_ONLY)
        return InteractionResult.SUCCESS
    }

    /**
     * In creative the loot table is skipped, so a full archive would vanish with its pages — the shulker
     * box's answer, which is to drop it as an item carrying them.
     */
    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        val archive = level.getBlockEntity(pos) as? ArchiveBlockEntity
        if (!level.isClientSide && player.preventsBlockDrops() && archive != null && !archive.pages.isEmpty) {
            val carried = ItemStack(this)
            carried.applyComponents(archive.collectComponents())
            val dropped = ItemEntity(level, pos.x + HALF, pos.y + HALF, pos.z + HALF, carried)
            dropped.setDefaultPickUpDelay()
            level.addFreshEntity(dropped)
        }
        return super.playerWillDestroy(level, pos, state, player)
    }

    companion object {

        private const val HALF = 0.5
    }
}

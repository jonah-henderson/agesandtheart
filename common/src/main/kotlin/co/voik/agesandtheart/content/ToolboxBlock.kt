package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/**
 * A case of shelves holding spares — see [Toolbox] for what carrying one does.
 *
 * **Furniture and luggage at once**, which is the whole of it: placed in a study it is one of the desk's
 * implements, and carried it is what puts a fresh pickaxe in your hand when the last one snaps.
 */
class ToolboxBlock(properties: Properties) : BaseEntityBlock(properties) {


    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = ToolboxBlockEntity(pos, state)

    /** Opening it is vanilla's own container flow; the nine compartments are a dispenser's screen. */
    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hit: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val toolbox = level.getBlockEntity(pos) as? ToolboxBlockEntity ?: return InteractionResult.PASS
        player.openMenu(toolbox)
        return InteractionResult.CONSUME
    }

    /**
     * Broken by somebody who gets no drops — a creative player — the box hands itself over anyway.
     *
     * **The other half of suppressing the scatter**, and the shulker box's own answer. Once
     * [ToolboxBlockEntity.preRemoveSideEffects] stops emptying the box onto the floor, the loot table is
     * the only thing carrying the spares away — and a creative break runs no loot table, so without this
     * the contents would simply cease to exist. Vanilla's chests get away with having neither because they
     * scatter.
     */
    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        val toolbox = level.getBlockEntity(pos) as? ToolboxBlockEntity
        if (toolbox != null && !level.isClientSide && player.preventsBlockDrops() && !toolbox.isEmpty) {
            val handed = ItemStack(state.block)
            handed.applyComponents(toolbox.collectComponents())
            val dropped = ItemEntity(level, pos.x + MIDDLE, pos.y + MIDDLE, pos.z + MIDDLE, handed)
            dropped.setDefaultPickUpDelay()
            level.addFreshEntity(dropped)
        }
        return super.playerWillDestroy(level, pos, state, player)
    }

    companion object {

        private const val MIDDLE = 0.5
    }
}

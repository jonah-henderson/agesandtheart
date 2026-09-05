package co.voik.agesandtheart.content

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
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

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

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

    companion object {
        val CODEC: MapCodec<ToolboxBlock> = simpleCodec(::ToolboxBlock)
    }
}

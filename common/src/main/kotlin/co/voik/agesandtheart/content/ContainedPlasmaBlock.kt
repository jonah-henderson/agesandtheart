package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.tags.ItemTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.redstone.Orientation
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.server.TickTask
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.LanternBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty

/**
 * A containment unit of plasma set down like a lantern, standing or hanging, and lighting what is near it.
 *
 * A player who breaks it, with any tool or none, takes the unit back: [playerWillDestroy] marks it [TAKEN],
 * and the loot table drops only a taken unit. Anything else that removes it — an explosion, a piston, a
 * hanging unit losing what it hung from — lets the plasma loose where it stood.
 *
 * **And whatever would light TNT lets it loose on purpose**: a redstone signal, flint and steel or a fire
 * charge, a burning projectile, and fire, which takes it as it takes TNT (`CommonSetup` makes it flammable).
 */
class ContainedPlasmaBlock(properties: Properties) : LanternBlock(properties) {

    init {
        registerDefaultState(defaultBlockState().setValue(TAKEN, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        super.createBlockStateDefinition(builder)
        builder.add(TAKEN)
    }

    override fun onPlace(state: BlockState, level: Level, pos: BlockPos, oldState: BlockState, movedByPiston: Boolean) {
        super.onPlace(state, level, pos, oldState, movedByPiston)
        if (!oldState.`is`(this) && level.hasNeighborSignal(pos)) letLoose(level, pos)
    }

    override fun neighborChanged(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        block: Block,
        orientation: Orientation?,
        movedByPiston: Boolean,
    ) {
        if (level.hasNeighborSignal(pos)) letLoose(level, pos)
    }

    override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (!stack.`is`(ItemTags.CREEPER_IGNITERS)) return super.useItemOn(stack, state, level, pos, player, hand, hitResult)
        letLoose(level, pos)
        if (stack.isDamageableItem) stack.hurtAndBreak(1, player, hand.asEquipmentSlot()) else stack.consume(1, player)
        return InteractionResult.SUCCESS
    }

    override fun onProjectileHit(level: Level, state: BlockState, hit: BlockHitResult, projectile: Projectile) {
        val struck = hit.blockPos
        val lights = level is ServerLevel && projectile.isOnFire && projectile.mayInteract(level, struck)
        if (lights) letLoose(level, struck)
    }

    /** Removed as anything but a player removes it, which the removal below answers by letting the plasma loose. */
    private fun letLoose(level: Level, pos: BlockPos) {
        if (!level.isClientSide) level.removeBlock(pos, false)
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        val taken = state.setValue(TAKEN, true)
        level.setBlock(pos, taken, UPDATE_SILENTLY)
        return super.playerWillDestroy(level, pos, taken, player)
    }

    /** Released a tick later rather than here, where the chunk is still in the middle of removing it. */
    override fun affectNeighborsAfterRemoval(state: BlockState, level: ServerLevel, pos: BlockPos, movedByPiston: Boolean) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston)
        if (state.getValue(TAKEN)) return
        val server = level.server
        server.schedule(TickTask(server.tickCount + 1) { level.setBlockAndUpdate(pos, Plasma.UNSTABLE.defaultBlockState()) })
    }

    companion object {
        /** Being broken by a player, who takes the unit back rather than letting it loose. */
        val TAKEN: BooleanProperty = BooleanProperty.create("taken")

        /** No neighbour told and nothing sent: the mark is read only by the removal that follows at once. */
        private const val UPDATE_SILENTLY = UPDATE_INVISIBLE or UPDATE_KNOWN_SHAPE
    }
}

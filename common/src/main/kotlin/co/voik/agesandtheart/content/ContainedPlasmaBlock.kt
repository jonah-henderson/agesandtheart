package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.tags.ItemTags
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Explosion
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.ScheduledTickAccess
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.redstone.Orientation
import net.minecraft.world.phys.BlockHitResult

/**
 * A containment unit of plasma set down on something solid. Broken, pushed or left unsupported it drops itself
 * as any block does.
 *
 * **Whatever would set off TNT lets the plasma loose instead**: a redstone signal, flint and steel or a fire
 * charge, a burning projectile, fire beside it, and an explosion, which leaves nothing to drop.
 */
class ContainedPlasmaBlock(properties: Properties) : ContainmentUnitBlock(properties) {

    override fun onPlace(state: BlockState, level: Level, pos: BlockPos, oldState: BlockState, movedByPiston: Boolean) {
        super.onPlace(state, level, pos, oldState, movedByPiston)
        if (oldState.`is`(this)) return
        val isSetOff = level.hasNeighborSignal(pos) || Direction.entries.any { isFire(level.getBlockState(pos.relative(it))) }
        if (isSetOff) letLoose(level, pos)
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

    /** Fire set beside it is answered on the next tick, since a shape update cannot change the level. */
    override fun updateShape(
        state: BlockState,
        level: LevelReader,
        ticks: ScheduledTickAccess,
        pos: BlockPos,
        direction: Direction,
        neighbourPos: BlockPos,
        neighbourState: BlockState,
        random: RandomSource,
    ): BlockState {
        if (isFire(neighbourState)) ticks.scheduleTick(pos, this, 1)
        return super.updateShape(state, level, ticks, pos, direction, neighbourPos, neighbourState, random)
    }

    override fun tick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        letLoose(level, pos)
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

    override fun dropFromExplosion(explosion: Explosion): Boolean = false

    /** Called once the explosion has cleared the unit, so the plasma takes its place at once. */
    override fun wasExploded(level: ServerLevel, pos: BlockPos, explosion: Explosion) {
        letLoose(level, pos)
    }

    /** The unit gives way to the plasma in it, dropping nothing. */
    private fun letLoose(level: Level, pos: BlockPos) {
        if (!level.isClientSide) level.setBlockAndUpdate(pos, Plasma.UNSTABLE.defaultBlockState())
    }

    private fun isFire(state: BlockState) = state.`is`(BlockTags.FIRE)
}

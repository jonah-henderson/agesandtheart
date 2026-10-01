package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.BlockTransformers
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.phys.BlockHitResult

/**
 * A log of ours that an axe strips into [strippedInto], keeping its axis.
 *
 * **Stripped here rather than through the axe's block transformer**: 26.3 made that a datapack registry
 * holding one `minecraft:axe` entry, and joining it would mean replacing vanilla's whole list. An axe is
 * whatever carries that transformer, so any axe a pack adds strips this too.
 */
open class StrippableLogBlock(
    properties: Properties,
    private val strippedInto: () -> Block,
) : RotatedPillarBlock(properties) {

    override fun useItemOn(
        itemStack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hitResult: BlockHitResult,
    ): InteractionResult {
        val isAnAxe = itemStack.get(DataComponents.BLOCK_TRANSFORMER)?.`is`(BlockTransformers.AXE) == true
        if (!isAnAxe) return super.useItemOn(itemStack, state, level, pos, player, hand, hitResult)
        val stripped = strippedInto().defaultBlockState().setValue(AXIS, state.getValue(AXIS))
        level.playSound(player, pos, SoundEvents.AXE_STRIP.value(), SoundSource.BLOCKS, 1.0f, 1.0f)
        if (!level.isClientSide) {
            level.setBlock(pos, stripped, UPDATE_ALL_IMMEDIATE)
            level.gameEvent(GameEvent.BLOCK_CHANGE, pos, GameEvent.Context.of(player, stripped))
            itemStack.hurtAndBreak(1, player, hand.asEquipmentSlot())
        }
        return InteractionResult.SUCCESS
    }
}

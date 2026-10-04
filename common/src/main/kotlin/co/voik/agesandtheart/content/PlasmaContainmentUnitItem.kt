package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemUtils
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult

/**
 * An empty unit of deretheni, which takes a block of a plasma sea into itself and becomes contained plasma,
 * the sea filling the gap again from beside — and which, aimed at anything else, is set down like a lantern.
 *
 * Aimed through `ClipContext.Block.VISUAL`, because plasma has no outline for an ordinary click to stop on:
 * a click can pass through the sea to the ground under it, and the sea is asked first so that it still fills.
 */
class PlasmaContainmentUnitItem(block: Block, properties: Properties) : BlockItem(block, properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        val sea = seaAimedAt(level, player) ?: return InteractionResult.PASS
        return fill(level, player, hand, sea)
    }

    override fun useOn(context: UseOnContext): InteractionResult {
        val player = context.player ?: return super.useOn(context)
        val sea = seaAimedAt(context.level, player) ?: return super.useOn(context)
        return fill(context.level, player, context.hand, sea)
    }

    private fun seaAimedAt(level: Level, player: Player): BlockPos? {
        val eye = player.eyePosition
        val reach = eye.add(player.lookAngle.scale(player.blockInteractionRange()))
        val hit = level.clip(ClipContext(eye, reach, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player))
        val aimsAtTheSea = hit.type == HitResult.Type.BLOCK && level.getBlockState(hit.blockPos).`is`(Plasma.SEA)
        return if (aimsAtTheSea) (hit as BlockHitResult).blockPos else null
    }

    private fun fill(level: Level, player: Player, hand: InteractionHand, taken: BlockPos): InteractionResult {
        level.playSound(player, taken, SoundEvents.BUCKET_FILL_LAVA, SoundSource.BLOCKS, 1.0f, 1.0f)
        if (!level.isClientSide) level.setBlockAndUpdate(taken, Blocks.AIR.defaultBlockState())
        val filled = ItemUtils.createFilledResult(player.getItemInHand(hand), player, ItemStack(Plasma.CONTAINED_ITEM))
        return InteractionResult.SUCCESS.heldItemTransformedTo(filled)
    }
}

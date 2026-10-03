package co.voik.agesandtheart.content

import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemUtils
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult

/**
 * An empty unit of deretheni, which takes a block of a plasma sea into itself and becomes contained plasma.
 * The sea fills the gap again from beside.
 *
 * Aimed through `ClipContext.Block.VISUAL`, because plasma has no outline for an ordinary click to stop on.
 */
class PlasmaContainmentUnitItem(properties: Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        val held = player.getItemInHand(hand)
        val eye = player.eyePosition
        val reach = eye.add(player.lookAngle.scale(player.blockInteractionRange()))
        val hit = level.clip(ClipContext(eye, reach, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player))
        val aimsAtTheSea = hit.type == HitResult.Type.BLOCK && level.getBlockState(hit.blockPos).`is`(Plasma.SEA)
        if (!aimsAtTheSea) return InteractionResult.PASS
        val taken = (hit as BlockHitResult).blockPos
        level.playSound(player, taken, SoundEvents.BUCKET_FILL_LAVA, SoundSource.BLOCKS, 1.0f, 1.0f)
        if (!level.isClientSide) level.setBlockAndUpdate(taken, Blocks.AIR.defaultBlockState())
        val filled = ItemUtils.createFilledResult(held, player, ItemStack(Plasma.CONTAINED_ITEM))
        return InteractionResult.SUCCESS.heldItemTransformedTo(filled)
    }
}

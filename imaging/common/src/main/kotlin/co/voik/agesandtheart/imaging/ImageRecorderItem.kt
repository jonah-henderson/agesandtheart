package co.voik.agesandtheart.imaging

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block

/**
 * The camera: used once to look through its [Viewfinder], and again to take the picture. Used against a block while
 * sneaking, it is set down there instead ([ImageRecorderBlock]); otherwise a block in reach is no different from the
 * open air, so the viewfinder can be raised at anything.
 */
class ImageRecorderItem(block: Block, properties: Properties) : BlockItem(block, properties) {

    override fun useOn(context: UseOnContext): InteractionResult {
        val isSettingItDown = context.player?.isSecondaryUseActive == true
        return if (isSettingItDown) super.useOn(context) else InteractionResult.PASS
    }

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        if (Viewfinder.isLooking(player)) {
            if (player is ServerPlayer) Viewfinder.shutter(player) else Viewfinder.shutterOnTheClient?.invoke()
            return InteractionResult.CONSUME
        }
        if (!Viewfinder.hasAPlate(player)) return InteractionResult.FAIL
        Viewfinder.lookThrough(player)
        return InteractionResult.CONSUME
    }
}

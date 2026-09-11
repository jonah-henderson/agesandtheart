package co.voik.agesandtheart.content

import co.voik.agesandtheart.desk.GeologistsToolsMenuProvider
import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/**
 * Reads what is in an Age's ground, where the seismograph reads whether it will hold (design §7.7).
 *
 * **It has a screen of its own rather than a line on the desk's**, which `SeismographBlock` said first and
 * this makes the rule: an implement that does something should be the thing you go and look at, not another
 * paragraph crowded onto one panel. The desk stays the place you *write*.
 */
class GeologistsToolsBlock(properties: BlockBehaviour.Properties) : Block(properties) {

    override fun codec(): MapCodec<GeologistsToolsBlock> = CODEC

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hit: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        (player as? ServerPlayer)?.openMenu(GeologistsToolsMenuProvider(pos))
        return InteractionResult.CONSUME
    }

    companion object {
        val CODEC: MapCodec<GeologistsToolsBlock> = simpleCodec(::GeologistsToolsBlock)
    }
}

package co.voik.agesandtheart.desk

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
 * Reads whether an Age's ground will hold, where the geologist's tools read what is in it (design §7.3).
 *
 * **It has a screen of its own rather than a line on the desk's** (Jonah, 2026-09-07), which is the
 * direction the whole upgrade set is moving in: an implement that does something should be the thing you
 * go and look at, not another paragraph crowded onto one panel. The desk stays the place you *write*.
 *
 * **A mass that does not belong to the ground it measures**, which is the instrument's real physics rather
 * than a pun: a seismometer works only because its bob stays put while the frame moves with the world.
 * Astrite is the one thing in the pack that is not of any Age, so the recipe hangs a shard of it from a
 * chain over a clock-driven drum.
 */
class SeismographBlock(properties: BlockBehaviour.Properties) : Block(properties) {

    override fun codec(): MapCodec<SeismographBlock> = CODEC

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hit: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        (player as? ServerPlayer)?.openMenu(SeismographMenuProvider(pos))
        return InteractionResult.CONSUME
    }

    companion object {
        val CODEC: MapCodec<SeismographBlock> = simpleCodec(::SeismographBlock)
    }
}

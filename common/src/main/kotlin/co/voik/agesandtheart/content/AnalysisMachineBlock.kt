package co.voik.agesandtheart.content

import co.voik.agesandtheart.age.word.Acquaintance
import co.voik.agesandtheart.age.word.Acquainted
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.BlockHitResult

/**
 * The D'ni analysis machine: hand it a thing, learn what the Art calls it (design §8.3).
 *
 * **A station, because the referent comes to it** — the shape follows what it names. Its twin, the
 * surveying device, is carried to the place and set down there, because a place cannot be brought to a
 * bench.
 *
 * **No screen and no block entity, deliberately.** The whole interaction is one sample and one answer,
 * and a slot to put a block in, a button to press and a result to take back out would be three steps
 * where the game already has one: you use the thing you are holding on the machine. There is no state
 * worth persisting between two uses.
 *
 * **The sample is the price** — a block studied is a block consumed, which is what makes "earn the diamond
 * block" bite. Only a *successful* study spends it: a refusal hands the sample back, since being told no
 * should not also cost you something.
 */
class AnalysisMachineBlock(properties: Properties) : Block(properties) {

    /**
     * Every outcome answers, including the ones that learn nothing.
     *
     * Falling through would let vanilla place the block instead — the commonest thing to offer the machine
     * is a block item, so a machine that sometimes built a wall in front of itself would be worse than one
     * that simply says it has nothing to tell you.
     */
    override fun useItemOn(
        itemStack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val writer = player as? ServerPlayer ?: return InteractionResult.FAIL
        val outcome = Acquaintance.withSubstance(writer, itemStack)
        Acquaintance.tell(writer, outcome)
        if (outcome is Acquainted.Learned) {
            itemStack.consume(1, player)
            level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, VOLUME, PITCH)
        }
        return InteractionResult.SUCCESS
    }

    /** An empty hand gets the instructions, since a machine with no screen has nowhere else to put them. */
    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val writer = player as? ServerPlayer ?: return InteractionResult.FAIL
        writer.sendSystemMessage(Component.translatable("device.agesandtheart.analysis_machine.hint"), true)
        return InteractionResult.SUCCESS
    }

    private companion object {
        const val VOLUME = 1.0f
        const val PITCH = 1.0f
    }
}

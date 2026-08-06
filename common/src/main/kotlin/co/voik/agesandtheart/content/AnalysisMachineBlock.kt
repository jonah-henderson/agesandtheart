package co.voik.agesandtheart.content

import co.voik.agesandtheart.age.word.Acquaintance
import co.voik.agesandtheart.age.word.Acquainted
import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.phys.BlockHitResult

/**
 * The D'ni analysis machine: feed it a thing, wait, and learn what the Art calls it (design §8.3).
 *
 * **A station, because the referent comes to it** — the shape follows what it names. Its twin the surveying
 * device is carried to the place and set down there, because a place cannot be brought to a bench.
 *
 * **Three stages, the same three the surveying device has** ([DeviceStage]). Feeding it is one act, the
 * work is another thing entirely, and collecting is a third — which is what makes a study read as *study*
 * rather than as a lookup. It began as one interaction that answered instantly, and the pair read as two
 * unrelated blocks for it (Jonah, 2026-08-06, walked).
 *
 * **It needs a block entity where the surveying device does not**, and the difference is worth knowing: a
 * place cannot move, so a survey can re-read the ground when it finishes, but a sample is *destroyed* going
 * in. What the machine is holding therefore has to be remembered across the wait, and that is the whole of
 * what [AnalysisMachineBlockEntity] keeps.
 *
 * **The sample is the price** — a block studied is a block consumed, which is what makes "earn the diamond
 * block" bite. Refused samples are never taken: everything the machine could object to is asked *before*
 * the block leaves your hand, so being told no never also costs you something.
 */
class AnalysisMachineBlock(properties: Properties) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(DeviceStage.PROPERTY, DeviceStage.IDLE))
    }

    override fun codec(): MapCodec<AnalysisMachineBlock> = CODEC

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        AnalysisMachineBlockEntity(pos, state)

    /** A model, not a renderer — the stage is a blockstate variant like the surveying device's. */
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState>) {
        builder.add(DeviceStage.PROPERTY)
    }

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
        val serverLevel = level as? ServerLevel ?: return InteractionResult.FAIL
        // Anything but an idle machine is busy or full, and what it has to say is the same either way.
        if (state.getValue(DeviceStage.PROPERTY) != DeviceStage.IDLE) {
            return useWithoutItem(state, level, pos, player, hitResult)
        }
        feed(serverLevel, pos, state, writer, itemStack)
        return InteractionResult.SUCCESS
    }

    /**
     * Takes a sample in, or says why it will not.
     *
     * The refusal is asked for *first* and the stack is only spent once the machine has committed to
     * working on it, so a sample that would teach nothing is handed back rather than eaten.
     */
    private fun feed(
        level: ServerLevel,
        pos: BlockPos,
        state: BlockState,
        writer: ServerPlayer,
        sample: ItemStack,
    ) {
        val substance = Acquaintance.substanceIn(sample)
        if (substance == null) {
            Acquaintance.tell(writer, Acquainted.Unnameable)
            return
        }
        val refusal = Acquaintance.refusalFor(writer, substance)
        if (refusal != null) {
            Acquaintance.tell(writer, refusal)
            return
        }
        val machine = level.getBlockEntity(pos) as? AnalysisMachineBlockEntity ?: return
        machine.holds = substance
        sample.consume(1, writer)
        level.setBlock(pos, state.setValue(DeviceStage.PROPERTY, DeviceStage.WORKING), UPDATE_ALL)
        level.scheduleTick(pos, this, DeviceStage.WORK_TICKS)
        level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, VOLUME, PITCH)
        say(writer, "device.agesandtheart.analysis_machine.started")
    }

    /** One empty-handed interaction, and what it does is whatever the machine is ready for. */
    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val writer = player as? ServerPlayer ?: return InteractionResult.FAIL
        val serverLevel = level as? ServerLevel ?: return InteractionResult.FAIL
        when (state.getValue(DeviceStage.PROPERTY)) {
            DeviceStage.IDLE -> say(writer, "device.agesandtheart.analysis_machine.hint")
            DeviceStage.WORKING -> stillRunning(serverLevel, pos, writer)
            DeviceStage.READY -> hand(serverLevel, pos, state, writer)
        }
        return InteractionResult.SUCCESS
    }

    /**
     * Says it is still working — and sets it going again if nothing is coming for it.
     *
     * The same guard the surveying device carries, for the same reason: the stage is a block state and the
     * wait is a scheduled tick, so anything writing the one without the other (`/setblock`, a structure
     * carrying one mid-run) leaves a machine that never finishes.
     */
    private fun stillRunning(level: ServerLevel, pos: BlockPos, writer: ServerPlayer) {
        if (!level.blockTicks.hasScheduledTick(pos, this)) level.scheduleTick(pos, this, DeviceStage.WORK_TICKS)
        say(writer, "device.agesandtheart.analysis_machine.working")
    }

    override fun tick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        if (state.getValue(DeviceStage.PROPERTY) != DeviceStage.WORKING) return
        level.setBlock(pos, state.setValue(DeviceStage.PROPERTY, DeviceStage.READY), UPDATE_ALL)
        level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, VOLUME, PITCH)
    }

    /**
     * The reading, handed over, and the machine emptied whatever came of it.
     *
     * A word learned between feeding and collecting comes back `AlreadyKnown`, which costs the sample that
     * was already spent — right, and the only case where the price is paid for nothing: the machine cannot
     * un-grind what it has ground.
     */
    private fun hand(level: ServerLevel, pos: BlockPos, state: BlockState, writer: ServerPlayer) {
        val machine = level.getBlockEntity(pos) as? AnalysisMachineBlockEntity
        val held = machine?.holds
        if (held == null) {
            level.setBlock(pos, state.setValue(DeviceStage.PROPERTY, DeviceStage.IDLE), UPDATE_ALL)
            return
        }
        val outcome = Acquaintance.teach(writer, held)
        Acquaintance.tell(writer, outcome)
        if (outcome is Acquainted.Learned) {
            level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, VOLUME, PITCH)
        }
        machine.holds = null
        level.setBlock(pos, state.setValue(DeviceStage.PROPERTY, DeviceStage.IDLE), UPDATE_ALL)
    }

    private fun say(writer: ServerPlayer, key: String) {
        writer.sendSystemMessage(Component.translatable(key), true)
    }

    /** A working machine is visibly working, which is the only thing the wait has to say for itself. */
    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        if (state.getValue(DeviceStage.PROPERTY) != DeviceStage.WORKING) return
        level.addParticle(
            ParticleTypes.ENCHANT,
            pos.x + random.nextDouble(),
            pos.y + ABOVE_THE_MACHINE,
            pos.z + random.nextDouble(),
            0.0,
            DRIFTING_UP,
            0.0,
        )
    }

    companion object {
        val CODEC: MapCodec<AnalysisMachineBlock> = simpleCodec(::AnalysisMachineBlock)

        private const val ABOVE_THE_MACHINE = 1.1
        private const val DRIFTING_UP = 0.04
        private const val VOLUME = 1.0f
        private const val PITCH = 1.0f
    }
}

/**
 * What the machine is chewing on — one referent, held across the wait.
 *
 * The sample is destroyed going in (see [AnalysisMachineBlock]), so this is the only record that it was
 * ever there. Breaking the machine mid-run loses it, which is the same bargain the surveying device makes
 * and costs the same thing: another sample and another wait.
 */
class AnalysisMachineBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(AgeContent.ANALYSIS_MACHINE_ENTITY, pos, state) {

    var holds: net.minecraft.resources.Identifier? = null
        set(value) {
            field = value
            setChanged()
        }

    override fun loadAdditional(input: net.minecraft.world.level.storage.ValueInput) {
        super.loadAdditional(input)
        holds = input.read(HELD_KEY, net.minecraft.resources.Identifier.CODEC).orElse(null)
    }

    override fun saveAdditional(output: net.minecraft.world.level.storage.ValueOutput) {
        super.saveAdditional(output)
        holds?.let { output.store(HELD_KEY, net.minecraft.resources.Identifier.CODEC, it) }
    }

    private companion object {
        const val HELD_KEY = "holds"
    }
}

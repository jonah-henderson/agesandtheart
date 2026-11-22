package co.voik.agesandtheart.optics

import co.voik.agesandtheart.content.RimeColour
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.ARGB
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.DirectionalBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.BlockHitResult

/**
 * An astrite emitter: sunlight reaching its back is focused through its lens into a beam out of its front, as long
 * as the sunlight is bright, and coloured by a rime crystal slotted in. A redstone signal puts it out.
 */
class AstriteEmitterBlock(properties: Properties) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    /** Shining away from its placer, so its back is toward them. */
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.nearestLookingDirection)

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = AstriteEmitterBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
        if (level.isClientSide) null
        else createTickerHelper(type, Optics.EMITTER_ENTITY) { tickedIn, _, _, emitter -> emitter.tick(tickedIn as ServerLevel) }

    override fun useItemOn(
        itemStack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hitResult: BlockHitResult,
    ): InteractionResult {
        val emitter = level.getBlockEntity(pos) ?: return InteractionResult.TRY_WITH_EMPTY_HAND
        val used = CrystalHolder.use(emitter, itemStack, player, level, pos)
        return if (used == InteractionResult.PASS) InteractionResult.TRY_WITH_EMPTY_HAND else used
    }

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
        val emitter = level.getBlockEntity(pos) ?: return InteractionResult.PASS
        return CrystalHolder.use(emitter, ItemStack.EMPTY, player, level, pos)
    }

    companion object {
        val FACING = DirectionalBlock.FACING
    }
}

class AstriteEmitterBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(Optics.EMITTER_ENTITY, pos, state), CrystalHolder {

    override var crystal: RimeColour? = null

    var path: BeamPath = BeamPath.DARK
        private set

    fun tick(level: ServerLevel) {
        if (level.gameTime % LOOKS_AGAIN_EVERY != 0L) return
        val traced = traced(level)
        for ((receiver, colour) in traced.reached) {
            (level.getBlockEntity(receiver) as? BeamReceiverBlockEntity)?.litBy(colour, level.gameTime + LIT_FOR)
        }
        if (traced.segments == path.segments) return
        path = traced
        setChanged()
        level.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    private fun traced(level: ServerLevel): BeamPath {
        val facing = blockState.getValue(AstriteEmitterBlock.FACING)
        val sunlight = level.getEffectiveSkyBrightness(worldPosition.relative(facing.opposite))
        val isPutOut = level.hasNeighborSignal(worldPosition)
        if (sunlight <= 0 || isPutOut) return BeamPath.DARK
        val range = (MOST_RANGE * sunlight + BRIGHTEST - 1) / BRIGHTEST
        val colour = crystal?.let { ARGB.opaque(it.tint) } ?: Beam.WHITE
        return Beam.trace(level, worldPosition, facing, colour, range)
    }

    override fun preRemoveSideEffects(pos: BlockPos, state: BlockState) {
        level?.let { CrystalHolder.drop(this, it, pos) }
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        crystal = CrystalHolder.load(input)
        path = BeamPath(input.read(SEGMENTS_KEY, BeamSegment.CODEC.listOf()).orElse(emptyList()), emptyList())
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        CrystalHolder.save(this, output)
        if (path.segments.isNotEmpty()) output.store(SEGMENTS_KEY, BeamSegment.CODEC.listOf(), path.segments)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveCustomOnly(registries)

    private companion object {
        const val SEGMENTS_KEY = "beam"

        /** How often the beam is followed again, in ticks, and how long a receiver stays lit by one look. */
        const val LOOKS_AGAIN_EVERY = 10L
        const val LIT_FOR = 12L

        /** The beam's reach in full sunlight; dimmer light reaches proportionally less far. */
        const val MOST_RANGE = 32
        const val BRIGHTEST = 15
    }
}

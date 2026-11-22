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
 * An astrite emitter: light reaching its back, from a conduit or a beam, is focused through its lens into a laser out
 * of its front, in the colour it was fed or that of a rime crystal slotted in. A redstone signal puts it out.
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
    BlockEntity(Optics.EMITTER_ENTITY, pos, state), CrystalHolder, ShinesABeam, TakesBeams {

    override var crystal: RimeColour? = null

    override var path: BeamPath = BeamPath.DARK
        private set

    private val atItsBack = IncomingBeams()

    /** A beam travelling the way it faces has come in at its back. */
    override fun takeBeam(source: BlockPos, travelling: Direction, light: Light, until: Long) {
        if (travelling == blockState.getValue(AstriteEmitterBlock.FACING)) atItsBack.take(source, light, until)
    }

    fun tick(level: ServerLevel) {
        val traced = traced(level)
        Beam.deliver(level, worldPosition, traced)
        if (traced.segments == path.segments) return
        path = traced
        setChanged()
        level.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    private fun traced(level: ServerLevel): BeamPath {
        val fed = Light.together(listOfNotNull(atItsBack.lightAt(level.gameTime), LightNetworks.lightFor(level, worldPosition)))
        val isPutOut = level.hasNeighborSignal(worldPosition)
        if (fed == null || isPutOut) return BeamPath.DARK
        val laser = crystal?.let { fed.copy(colour = it) } ?: fed
        return Beam.trace(level, worldPosition, blockState.getValue(AstriteEmitterBlock.FACING), laser, RANGE, diffuse = false)
    }

    override fun preRemoveSideEffects(pos: BlockPos, state: BlockState) {
        level?.let { CrystalHolder.drop(this, it, pos) }
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        crystal = CrystalHolder.load(input)
        path = BeamPath(input.read(SEGMENTS_KEY, BeamSegment.CODEC.listOf()).orElse(emptyList()), null)
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
        const val RANGE = 32
    }
}

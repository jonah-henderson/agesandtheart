package co.voik.agesandtheart.optics

import co.voik.agesandtheart.optics.ReflectorBlock.Mirror
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.BlockHitResult

/**
 * The faces of a splitter or merger, which are a half-silvered reflector: the straight face (`first`), on the axis a
 * beam carries straight along, and the turned face (`second`), at right angles to it. Placed as a reflector of the same
 * plane is; using a face on the straight axis makes it the straight face, and on the horizontal form any other makes it
 * the turned one.
 */
object HalfSilvered {
    val FACES: EnumProperty<Mirror> = EnumProperty.create("faces", Mirror::class.java)

    /** The faces after [face], a level face not already open, is used on the horizontal form. */
    fun levelOpening(faces: Mirror, face: Direction): Mirror = when (face.axis) {
        faces.first.axis -> Mirror.of(face, faces.second)
        else -> Mirror.of(faces.first, face)
    }

    fun use(plane: OpticPlane, state: BlockState, level: Level, pos: BlockPos, hitResult: BlockHitResult): InteractionResult {
        val opened = plane.opening(state.getValue(FACES), hitResult.direction, ::levelOpening) ?: return InteractionResult.PASS
        if (!level.isClientSide) {
            level.setBlock(pos, state.setValue(FACES, opened), Block.UPDATE_ALL)
            level.playSound(null, pos, SoundEvents.SPYGLASS_USE, SoundSource.BLOCKS, 1f, 1f)
        }
        return InteractionResult.SUCCESS
    }
}

/**
 * A beam splitter, in the horizontal or the vertical [plane]: a beam coming in at its straight face goes on through at
 * half its intensity, and the other half turns out of its turned face. A beam reaching any other face stops.
 */
class BeamSplitterBlock(properties: Properties, val plane: OpticPlane) : Block(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(HalfSilvered.FACES, OpticPlane.resting(plane)))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(HalfSilvered.FACES)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(HalfSilvered.FACES, plane.placed(context))

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult =
        HalfSilvered.use(plane, state, level, pos, hitResult)

    companion object {
        /** Where the turned half of a beam travelling [direction] into a splitter in [state] leaves it, or none where it stops. */
        fun turnedOut(state: BlockState, direction: Direction): Direction? {
            val faces = state.getValue(HalfSilvered.FACES)
            return if (direction.opposite == faces.first) faces.second else null
        }
    }
}

/**
 * A beam merger, in the horizontal or the vertical [plane]: a beam coming in at the face opposite its straight face, and one coming in at its turned face, leave
 * together by the straight face, their intensities added and their colours mixed. Unfocused only where everything
 * reaching it is.
 */
class BeamMergerBlock(properties: Properties, val plane: OpticPlane) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(HalfSilvered.FACES, OpticPlane.resting(plane)))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(HalfSilvered.FACES)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(HalfSilvered.FACES, plane.placed(context))

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult =
        HalfSilvered.use(plane, state, level, pos, hitResult)

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = BeamMergerBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
        if (level.isClientSide) null
        else createTickerHelper(type, Optics.MERGER_ENTITY) { tickedIn, _, _, merger -> merger.tick(tickedIn as ServerLevel) }
}

class BeamMergerBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(Optics.MERGER_ENTITY, pos, state), ShinesABeam, TakesBeams {

    override var path: BeamPath = BeamPath.DARK
        private set

    private val coming = IncomingBeams()

    private val faces: Mirror get() = blockState.getValue(HalfSilvered.FACES)

    /** A beam travelling toward its straight face, through the face opposite or in at its turned face. */
    override fun takeBeam(source: BlockPos, travelling: Direction, light: Light, diffuse: Boolean, until: Long) {
        val isComingStraight = travelling == faces.first
        val isComingInTurned = travelling == faces.second.opposite
        if (isComingStraight || isComingInTurned) coming.take(source, travelling, light, diffuse, until)
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
        val merged = coming.lightAt(level.gameTime) ?: return BeamPath.DARK
        val diffuse = coming.isAllDiffuseAt(level.gameTime)
        val range = if (diffuse) DIFFUSE_RANGE else LASER_RANGE
        return Beam.trace(level, worldPosition, faces.first, merged, range, diffuse)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        path = BeamPath(input.read(SEGMENTS_KEY, BeamSegment.CODEC.listOf()).orElse(emptyList()), emptyList())
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        if (path.segments.isNotEmpty()) output.store(SEGMENTS_KEY, BeamSegment.CODEC.listOf(), path.segments)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveCustomOnly(registries)

    private companion object {
        const val SEGMENTS_KEY = "beam"
        const val LASER_RANGE = 32
        const val DIFFUSE_RANGE = 16
    }
}

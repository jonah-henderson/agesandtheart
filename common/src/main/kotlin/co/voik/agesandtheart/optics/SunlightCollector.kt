package co.voik.agesandtheart.optics

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.StringRepresentable
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import org.joml.Vector3f

/** Which of a collector's models: the base it stands on, or the dish its renderer turns toward the sun. */
enum class CollectorPiece(private val serializedName: String) : StringRepresentable {
    BASE("base"),
    DISH("dish"),
    ;

    override fun getSerializedName(): String = serializedName
}

/**
 * A sunlight collector: full white light in bright sky light, sent out of the face away from whoever placed it, into
 * a conduit there or else as a diffuse beam, which is not focused enough to be a laser until an emitter focuses it.
 */
class SunlightCollectorBlock(properties: Properties) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PIECE, CollectorPiece.BASE))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, PIECE)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.horizontalDirection)

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = SunlightCollectorBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
        if (level.isClientSide) null
        else createTickerHelper(type, Optics.COLLECTOR_ENTITY) { tickedIn, _, _, collector -> collector.tick(tickedIn as ServerLevel) }

    companion object {
        /** The face its light leaves by. */
        val FACING: EnumProperty<Direction> = HorizontalDirectionalBlock.FACING
        val PIECE: EnumProperty<CollectorPiece> = EnumProperty.create("piece", CollectorPiece::class.java)

        private val SHAPE: VoxelShape = box(0.0, 0.0, 0.0, 16.0, 10.0, 16.0)
    }
}

class SunlightCollectorBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(Optics.COLLECTOR_ENTITY, pos, state), ShinesABeam {

    override var path: BeamPath = BeamPath.DARK
        private set

    /** Which way the dish faces, as the client last drew it, and when. Never saved. */
    var dishFacing: Vector3f = Vector3f(0f, 1f, 0f)
    var dishLastTurnedNanos: Long = 0L

    /** Full white light while the sky light here is bright enough, or null. */
    fun gathered(level: Level): Light? {
        val skyLight = maxOf(level.getEffectiveSkyBrightness(worldPosition), level.getEffectiveSkyBrightness(worldPosition.above()))
        return if (skyLight >= BRIGHT_ENOUGH) Light.SUNLIGHT else null
    }

    fun tick(level: ServerLevel) {
        val facing = blockState.getValue(SunlightCollectorBlock.FACING)
        val feedsAConduit = level.getBlockState(worldPosition.relative(facing)).block is OpticalConduitBlock
        val light = gathered(level)
        val traced = if (light == null || feedsAConduit) BeamPath.DARK
        else Beam.trace(level, worldPosition, facing, light, DIFFUSE_RANGE, diffuse = true)
        Beam.deliver(level, worldPosition, traced)
        if (traced.segments == path.segments) return
        path = traced
        setChanged()
        level.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_CLIENTS)
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
        const val DIFFUSE_RANGE = 16

        /** Sky light, of 15, at which a collector gathers: noon under rain still is, four blocks into a cave is not. */
        const val BRIGHT_ENOUGH = 12
    }
}

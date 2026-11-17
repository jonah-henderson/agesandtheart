package co.voik.agesandtheart.imaging

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Prediction
import net.minecraft.world.ContainerHelper
import net.minecraft.world.Containers
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.redstone.Orientation
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.BlockHitResult

/**
 * The projector's slide carousel: sixteen recorded images round a wheel, the one at the front thrown through the lens
 * ahead of it. A recorded image used on it goes into the front slot, or the next empty one; an empty hand takes out the
 * front slide; sneaking with an empty hand turns it by one, as each pulse of redstone does. A comparator reads which
 * slot is at the front.
 */
class SlideCarouselBlock(properties: Properties) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(POWERED, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, POWERED)
    }

    /** Throwing away from whoever sets it down, toward the wall they were looking at. */
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.horizontalDirection)

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = SlideCarouselBlockEntity(pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (!stack.`is`(ImagingContent.RECORDED_IMAGE)) return InteractionResult.TRY_WITH_EMPTY_HAND
        val carousel = level.getBlockEntity(pos) as? SlideCarouselBlockEntity ?: return InteractionResult.PASS
        if (!carousel.hasRoom()) return InteractionResult.FAIL
        if (!level.isClientSide) carousel.load(stack.consumeAndReturn(1, player))
        return InteractionResult.SUCCESS
    }

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
        val carousel = level.getBlockEntity(pos) as? SlideCarouselBlockEntity ?: return InteractionResult.PASS
        if (player.isSecondaryUseActive) {
            if (!level.isClientSide) carousel.turn()
            return InteractionResult.SUCCESS
        }
        if (carousel.front().isEmpty) return InteractionResult.PASS
        if (!level.isClientSide) player.inventory.placeItemBackInInventory(carousel.takeTheFront(), Prediction.SERVER_ONLY)
        return InteractionResult.SUCCESS
    }

    /** A pulse turns it by one, on its rising edge only, as a dispenser fires. */
    override fun neighborChanged(state: BlockState, level: Level, pos: BlockPos, neighbour: Block, orientation: Orientation?, movedByPiston: Boolean) {
        val isPowered = level.hasNeighborSignal(pos)
        val wasPowered = state.getValue(POWERED)
        if (isPowered == wasPowered) return
        level.setBlock(pos, state.setValue(POWERED, isPowered), UPDATE_CLIENTS)
        if (isPowered && !level.isClientSide) (level.getBlockEntity(pos) as? SlideCarouselBlockEntity)?.turn()
    }

    override fun hasAnalogOutputSignal(state: BlockState): Boolean = true

    override fun getAnalogOutputSignal(state: BlockState, level: Level, pos: BlockPos, direction: Direction): Int =
        (level.getBlockEntity(pos) as? SlideCarouselBlockEntity)?.frontSlotSignal() ?: 0

    override fun rotate(state: BlockState, rotation: Rotation): BlockState = state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState = state.rotate(mirror.getRotation(state.getValue(FACING)))

    companion object {
        val FACING: EnumProperty<Direction> = HorizontalDirectionalBlock.FACING
        val POWERED: BooleanProperty = BlockStateProperties.POWERED
    }
}

class SlideCarouselBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(ImagingContent.SLIDE_CAROUSEL_ENTITY, pos, state) {

    private val slides: NonNullList<ItemStack> = NonNullList.withSize(SLIDES, ItemStack.EMPTY)

    /** Which slot is at the front, before the lens. */
    var frontSlot = 0
        private set

    fun front(): ItemStack = slides[frontSlot]

    fun hasRoom(): Boolean = slides.any { it.isEmpty }

    /** Into the front slot where it is empty, otherwise the next empty one round; the wheel does not turn. */
    fun load(slide: ItemStack) {
        val slot = (0..<SLIDES).map { (frontSlot + it) % SLIDES }.firstOrNull { slides[it].isEmpty } ?: return
        slides[slot] = slide
        changed()
    }

    fun takeTheFront(): ItemStack {
        val taken = slides[frontSlot]
        slides[frontSlot] = ItemStack.EMPTY
        changed()
        return taken
    }

    fun turn() {
        frontSlot = (frontSlot + 1) % SLIDES
        val level = level as? ServerLevel ?: return
        level.playSound(null, blockPos, SoundEvents.DISPENSER_DISPENSE, SoundSource.BLOCKS, TURN_VOLUME, TURN_PITCH)
        changed()
    }

    /** The front slot's number, from one; the sixteenth reads as the fifteenth, which is as high as a signal goes. */
    fun frontSlotSignal(): Int = minOf(frontSlot + 1, MOST_SIGNAL)

    private fun changed() {
        setChanged()
        val level = level ?: return
        level.updateNeighbourForOutputSignal(blockPos, blockState.block)
        if (!level.isClientSide) level.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    override fun preRemoveSideEffects(pos: BlockPos, state: BlockState) {
        level?.let { Containers.dropContents(it, pos, slides) }
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        slides.clear()
        ContainerHelper.loadAllItems(input, slides)
        frontSlot = input.getIntOr(FRONT_KEY, 0).coerceIn(0, SLIDES - 1)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        ContainerHelper.saveAllItems(output, slides, true)
        output.putInt(FRONT_KEY, frontSlot)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveCustomOnly(registries)

    companion object {
        const val SLIDES = 16
        private const val MOST_SIGNAL = 15
        private const val FRONT_KEY = "front"
        private const val TURN_VOLUME = 0.6f
        private const val TURN_PITCH = 1.6f
    }
}

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
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.redstone.Orientation
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import net.minecraft.world.level.BlockGetter

/**
 * The projector's slide carousel: sixteen recorded images round a disk, the one at the front lifted by the selector arm
 * before the focusing lens and thrown through the lens ahead. A slide goes into whichever empty slot it is used on, and
 * an empty hand takes the slide out of whichever slot it is used on, the raised one included. Its two buttons turn it
 * either way, and each pulse of redstone turns it on by one. A comparator reads which slot is at the front.
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

    /** One box round the whole model, buttons and lens included, turned the way it faces. */
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        SHAPES.getValue(state.getValue(FACING))

    /** The machinery moves on the client, catching up with the front slot ([CarouselMotion]). */
    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
        if (level.isClientSide) createTickerHelper(type, ImagingContent.SLIDE_CAROUSEL_ENTITY) { _, _, _, carousel -> carousel.tickTheMachinery() } else null

    /**
     * One of the two buttons on the carousel's left flank, as it faces, where [hitResult] struck, or null. The block's
     * box reaches out to the buttons' faces, so a button is found by where on that side the click landed.
     */
    private fun buttonUnder(state: BlockState, pos: BlockPos, hitResult: BlockHitResult): CarouselButton? {
        val facing = state.getValue(FACING)
        if (hitResult.direction != facing.counterClockWise) return null
        val (_, modelY, modelZ) = CarouselButton.inTheModel(facing, hitResult.location.subtract(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()))
        return CarouselButton.entries.firstOrNull { it.covers(modelY, modelZ) }
    }

    private fun press(button: CarouselButton, level: Level, pos: BlockPos): InteractionResult {
        val carousel = level.getBlockEntity(pos) as? SlideCarouselBlockEntity ?: return InteractionResult.PASS
        if (!level.isClientSide) {
            level.playSound(null, pos, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, BUTTON_VOLUME, BUTTON_PITCH)
            level.blockEvent(pos, this, SlideCarouselBlockEntity.BUTTON_PRESSED, button.ordinal)
            carousel.turn(button.step)
        }
        return InteractionResult.SUCCESS
    }

    override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hitResult: BlockHitResult,
    ): InteractionResult {
        buttonUnder(state, pos, hitResult)?.let { return press(it, level, pos) }
        // A blank plate is a slide too, and throws plain light.
        val isASlide = stack.`is`(ImagingContent.RECORDED_IMAGE) || stack.`is`(ImagingContent.PHOTOGRAPHIC_PLATE)
        if (!isASlide) return InteractionResult.TRY_WITH_EMPTY_HAND
        val carousel = level.getBlockEntity(pos) as? SlideCarouselBlockEntity ?: return InteractionResult.PASS
        val slot = CarouselSlots.under(state, pos, player.eyePosition, hitResult.location, carousel) ?: return InteractionResult.PASS
        if (!carousel.slideIn(slot).isEmpty) return InteractionResult.FAIL
        if (!level.isClientSide) carousel.putIn(slot, stack.consumeAndReturn(1, player))
        return InteractionResult.SUCCESS
    }

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
        buttonUnder(state, pos, hitResult)?.let { return press(it, level, pos) }
        val carousel = level.getBlockEntity(pos) as? SlideCarouselBlockEntity ?: return InteractionResult.PASS
        val slot = CarouselSlots.under(state, pos, player.eyePosition, hitResult.location, carousel) ?: return InteractionResult.PASS
        if (carousel.slideIn(slot).isEmpty) return InteractionResult.PASS
        if (!level.isClientSide) player.inventory.placeItemBackInInventory(carousel.takeOut(slot), Prediction.SERVER_ONLY)
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
        /** The model's bounds facing north, in pixels: from the buttons to the base's far side, up to the lens's top. */
        private val SHAPES: Map<Direction, VoxelShape> = Shapes.rotateHorizontal(Block.box(0.5, 0.0, 0.5, 15.0, 10.0, 16.0))
        private const val BUTTON_VOLUME = 0.3f
        private const val BUTTON_PITCH = 1.2f
    }
}

/**
 * The carousel's two buttons, where they stand on its left flank in the model's own pixels, facing north, and which way
 * each turns the disk: clockwise seen from above is on, the way redstone turns it.
 */
enum class CarouselButton(private val fromZ: Double, private val toZ: Double, val step: Int) {
    CLOCKWISE(10.25, 11.25, 1),
    COUNTER_CLOCKWISE(11.75, 12.75, -1),
    ;

    fun covers(modelY: Double, modelZ: Double): Boolean {
        val isLevelWithIt = modelY in (LOWEST - REACH)..(HIGHEST + REACH)
        val isAlongIt = modelZ in (fromZ - REACH)..(toZ + REACH)
        return isLevelWithIt && isAlongIt
    }

    companion object {
        private const val LOWEST = 1.5
        private const val HIGHEST = 2.5
        private const val REACH = 0.2
        private const val PIXELS = 16.0

        /**
         * A point in the block, nought to one, as pixels of the model facing north: the blockstate turns the model
         * clockwise from above a quarter for each step from north, and this turns it back.
         */
        fun inTheModel(facing: Direction, inTheBlock: Vec3): Triple<Double, Double, Double> {
            var x = inTheBlock.x * PIXELS
            var z = inTheBlock.z * PIXELS
            repeat(Math.floorMod(facing.get2DDataValue() - Direction.NORTH.get2DDataValue(), QUARTERS)) {
                val turnedX = z
                z = PIXELS - x
                x = turnedX
            }
            return Triple(x, inTheBlock.y * PIXELS, z)
        }

        private const val QUARTERS = 4
    }
}

class SlideCarouselBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(ImagingContent.SLIDE_CAROUSEL_ENTITY, pos, state) {

    private val slides: NonNullList<ItemStack> = NonNullList.withSize(SLIDES, ItemStack.EMPTY)

    /** Which slot is at the front, before the lens. */
    var frontSlot = 0
        private set

    /** Which way it last turned, on (1) or back (-1), so a client turns its disk the same way. */
    var lastTurn = 1
        private set

    /** How the machinery stands on this client; never saved or sent. */
    val motion = CarouselMotion()

    /** When each button was last pressed, by [CarouselButton.ordinal], in this client's level time. */
    val pressedAt = LongArray(CarouselButton.entries.size) { NEVER }

    fun front(): ItemStack = slides[frontSlot]

    fun slideIn(slot: Int): ItemStack = slides[slot]

    fun tickTheMachinery() = motion.tick(frontSlot, { !slides[it].isEmpty }, lastTurn)

    override fun triggerEvent(id: Int, param: Int): Boolean {
        if (id != BUTTON_PRESSED) return super.triggerEvent(id, param)
        val level = level ?: return true
        if (param in pressedAt.indices) pressedAt[param] = level.gameTime
        return true
    }

    /** Its slides seen as it loads, so a carousel's pictures never shrink while it stands where somebody goes. */
    override fun setLevel(level: Level) {
        super.setLevel(level)
        val server = (level as? ServerLevel)?.server ?: return
        slides.forEach { slide -> slide.get(ImagingComponents.PICTURE)?.let { PicturesSeen.of(server).saw(it.id) } }
    }

    /** [slide] into [slot], which is empty; the disk does not turn. */
    fun putIn(slot: Int, slide: ItemStack) {
        if (!slides[slot].isEmpty) return
        slides[slot] = slide
        changed()
    }

    /** What stands in [slot], taken out of it. */
    fun takeOut(slot: Int): ItemStack {
        val taken = slides[slot]
        slides[slot] = ItemStack.EMPTY
        changed()
        return taken
    }

    /** On by [by] slots, on (positive) or back (negative). */
    fun turn(by: Int = 1) {
        frontSlot = Math.floorMod(frontSlot + by, SLIDES)
        lastTurn = if (by < 0) -1 else 1
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
        lastTurn = if (input.getIntOr(LAST_TURN_KEY, 1) < 0) -1 else 1
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        ContainerHelper.saveAllItems(output, slides, true)
        output.putInt(FRONT_KEY, frontSlot)
        output.putInt(LAST_TURN_KEY, lastTurn)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveCustomOnly(registries)

    companion object {
        const val SLIDES = 16
        private const val MOST_SIGNAL = 15
        private const val FRONT_KEY = "front"
        private const val LAST_TURN_KEY = "last_turn"

        /** The block event a button press is sent to clients as, its parameter the button. */
        const val BUTTON_PRESSED = 1
        private const val NEVER = Long.MIN_VALUE / 2
        private const val TURN_VOLUME = 0.6f
        private const val TURN_PITCH = 1.6f
    }
}

/**
 * Which slot a player is reaching for: where their line of sight, from their eye through the point they clicked on the
 * carousel's box, meets the raised slide or the top of the disk. The box stands well above the disk, so the click itself
 * says only which way they were looking. Measured in the model's own pixels, facing north, as Jonah's model is drawn.
 */
object CarouselSlots {

    /** The slot under [eye]'s line through [clicked] on the carousel at [pos], or null where it misses the disk. */
    fun under(state: BlockState, pos: BlockPos, eye: Vec3, clicked: Vec3, carousel: SlideCarouselBlockEntity): Int? {
        val facing = state.getValue(SlideCarouselBlock.FACING)
        val corner = Vec3(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
        val (eyeX, eyeY, eyeZ) = CarouselButton.inTheModel(facing, eye.subtract(corner))
        val (clickedX, clickedY, clickedZ) = CarouselButton.inTheModel(facing, clicked.subtract(corner))
        val from = Vec3(eyeX, eyeY, eyeZ)
        val along = Vec3(clickedX - eyeX, clickedY - eyeY, clickedZ - eyeZ)
        val front = carousel.frontSlot
        if (!carousel.slideIn(front).isEmpty && meetsTheRaisedSlide(from, along)) return front
        if (along.y >= 0.0) return null
        val reach = (DISK_TOP - from.y) / along.y
        val acrossFromTheAxle = from.x + along.x * reach - AXLE_X
        val alongFromTheAxle = from.z + along.z * reach - AXLE_Z
        val distance = kotlin.math.hypot(acrossFromTheAxle, alongFromTheAxle)
        val isOnTheDisk = distance in HUB..RIM
        if (!isOnTheDisk) return null
        // The front slot stands out toward the model's west, and each slot on is a sixteenth of a turn round from it.
        val degrees = Math.toDegrees(kotlin.math.atan2(alongFromTheAxle, -acrossFromTheAxle))
        val slotsRound = Math.round(degrees / DEGREES_A_SLOT).toInt()
        return Math.floorMod(front + slotsRound, SlideCarouselBlockEntity.SLIDES)
    }

    /** Whether the line from [from] along [along] passes through the raised slide, the slab way. */
    private fun meetsTheRaisedSlide(from: Vec3, along: Vec3): Boolean {
        var nearest = 0.0
        var farthest = Double.MAX_VALUE
        for (axis in 0..<AXES) {
            val start = from.get(axis)
            val step = along.get(axis)
            val low = RAISED_LOW[axis]
            val high = RAISED_HIGH[axis]
            if (kotlin.math.abs(step) < TINY) {
                if (start < low || start > high) return false
                continue
            }
            val first = (low - start) / step
            val second = (high - start) / step
            nearest = maxOf(nearest, minOf(first, second))
            farthest = minOf(farthest, maxOf(first, second))
            if (nearest > farthest) return false
        }
        return true
    }

    private fun Vec3.get(axis: Int): Double = when (axis) {
        0 -> x
        1 -> y
        else -> z
    }

    /** Where the disk turns, its top, and the ring the slots stand on, from the hub out to the rim. */
    private const val AXLE_X = 8.0
    private const val AXLE_Z = 7.5
    private const val DISK_TOP = 4.0
    private const val HUB = 1.5
    private const val RIM = 7.2
    private const val DEGREES_A_SLOT = 360.0 / SlideCarouselBlockEntity.SLIDES

    /** The raised slide's box, before the focusing lens. */
    private val RAISED_LOW = doubleArrayOf(7.75, 7.0, 7.0)
    private val RAISED_HIGH = doubleArrayOf(11.75, 9.0, 8.0)
    private const val AXES = 3
    private const val TINY = 1.0e-9
}

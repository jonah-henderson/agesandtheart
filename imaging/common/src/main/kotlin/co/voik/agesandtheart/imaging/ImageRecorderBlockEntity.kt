package co.voik.agesandtheart.imaging

import co.voik.agesandtheart.age.word.WordNames
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.core.Direction
import net.minecraft.core.NonNullList
import net.minecraft.core.UUIDUtil
import net.minecraft.core.particles.ColorParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.ContainerHelper
import net.minecraft.world.WorldlyContainer
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block.UPDATE_CLIENTS
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * A set-down recorder's two slots ([RecorderSlots]): blank plates go in, from a hand or a hopper above or beside it, and
 * the recorded image comes out, by hand or to a hopper below, as a furnace's do. A pulse spends a plate on a picture of
 * what it faces, fires its flash, and puts the recorded image in the output, or out of the back where one is already
 * waiting there; with no plate it only clicks. The two slots go with the recorder when it is picked up, as a shulker
 * box's contents do, and the recorder in hand draws on them ([RecorderSlots.Held]).
 */
class ImageRecorderBlockEntity(pos: BlockPos, state: BlockState) :
    BaseContainerBlockEntity(ImagingContent.IMAGE_RECORDER_ENTITY, pos, state), WorldlyContainer {

    private var items: NonNullList<ItemStack> = NonNullList.withSize(RecorderSlots.COUNT, ItemStack.EMPTY)

    /** Whoever set it down, whose client is asked first for a photograph, and who stands as its pictures' photographer. */
    var owner: UUID? = null

    /** Which way it points on a tripod, as an entity's yaw and pitch are counted; on the ground its block state says. */
    var aimYaw: Float = 0.0f
        private set
    var aimPitch: Float = 0.0f
        private set

    /** How far it is zoomed, as a share of its widest view: one at its widest, a spyglass's tenth at its narrowest. */
    var zoom: Float = WIDEST
        private set

    /** The vertical field of view its pictures are taken at, its zoom included. */
    val fieldOfView: Float get() = Sketches.ORDINARY_FIELD_OF_VIEW * zoom

    fun zoomTo(share: Float) {
        zoom = share.coerceIn(NARROWEST, WIDEST)
        setChanged()
        val level = level ?: return
        if (!level.isClientSide) level.sendBlockUpdated(blockPos, blockState, blockState, UPDATE_CLIENTS)
    }

    /** Pointed along [yaw] and [pitch], told to whoever can see it. */
    fun aim(yaw: Float, pitch: Float) {
        aimYaw = Mth.wrapDegrees(yaw)
        aimPitch = Mth.clamp(pitch, -MOST_PITCH, MOST_PITCH)
        setChanged()
        val level = level ?: return
        if (!level.isClientSide) level.sendBlockUpdated(blockPos, blockState, blockState, UPDATE_CLIENTS)
    }

    override fun getContainerSize(): Int = RecorderSlots.COUNT

    override fun getItems(): NonNullList<ItemStack> = items

    override fun setItems(items: NonNullList<ItemStack>) {
        this.items = items
    }

    override fun getDefaultName(): Component = Component.translatable("block.agesandtheart_imaging.image_recorder")

    override fun createMenu(containerId: Int, inventory: Inventory): AbstractContainerMenu = ImageRecorderMenu(containerId, inventory, this)

    override fun canPlaceItem(slot: Int, stack: ItemStack): Boolean = RecorderSlots.belongsIn(slot, stack)

    override fun getSlotsForFace(side: Direction): IntArray = if (side == Direction.DOWN) PICTURE_SIDE else PLATE_SIDES

    override fun canPlaceItemThroughFace(slot: Int, stack: ItemStack, side: Direction?): Boolean =
        slot == RecorderSlots.PLATES && side != Direction.DOWN && RecorderSlots.isAPlate(stack)

    override fun canTakeItemThroughFace(slot: Int, stack: ItemStack, side: Direction): Boolean =
        slot == RecorderSlots.PICTURE && side == Direction.DOWN

    /** Its pictures seen as it loads, as a carousel's slides are, so they never shrink while it stands where somebody goes. */
    override fun setLevel(level: Level) {
        super.setLevel(level)
        val server = (level as? ServerLevel)?.server ?: return
        items.forEach { stack -> stack.get(ImagingComponents.PICTURE)?.let { PicturesSeen.of(server).saw(it.id) } }
    }

    /**
     * A picture of what it faces, on a plate of its own, with its flash, drawn by a client ([AutomaticShots]); or a
     * click where it has no plate.
     */
    fun shoot(level: ServerLevel) {
        val plates = items[RecorderSlots.PLATES]
        if (!RecorderSlots.isAPlate(plates)) {
            level.playSound(null, blockPos, SoundEvents.DISPENSER_FAIL, SoundSource.BLOCKS, 1.0f, CLICK_PITCH)
            return
        }
        plates.shrink(1)
        val picture = Picture(UUID.randomUUID(), WordNames.placeName(level.dimension()), blockPos)
        val recordedImage = ItemStack(ImagingContent.RECORDED_IMAGE).apply { set(ImagingComponents.PICTURE, picture) }
        if (items[RecorderSlots.PICTURE].isEmpty) items[RecorderSlots.PICTURE] = recordedImage else ejectOutTheBack(level, recordedImage)
        setChanged()
        level.updateNeighbourForOutputSignal(blockPos, blockState.block)
        PicturesSeen.of(level.server).saw(picture.id)

        val aim = ImageRecorderBlock.aimOf(level, blockPos) ?: return
        val lens = aim.lens
        val pose = Shots.Pose(level.dimension(), lens.x, lens.y, lens.z, aim.yaw, aim.pitch)
        AutomaticShots.commission(level, picture.id, owner, pose, fieldOfView)

        val flash = aim.flash
        level.playSound(null, blockPos, ImagingContent.SHUTTER, SoundSource.BLOCKS, 1.0f, 1.0f)
        level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, FLASH_COLOUR), flash.x, flash.y, flash.z, 1, 0.0, 0.0, 0.0, 0.0)
    }

    /** [stack] thrown gently out behind it, the way it does not look. */
    private fun ejectOutTheBack(level: ServerLevel, stack: ItemStack) {
        val aim = ImageRecorderBlock.aimOf(level, blockPos) ?: return
        val back = Vec3.directionFromRotation(LEVEL, aim.yaw).reverse()
        val from = aim.pivot.add(back.scale(OUT_OF_THE_BACK)).add(0.0, EJECTED_HEIGHT, 0.0)
        val thrown = ItemEntity(level, from.x, from.y, from.z, stack)
        thrown.deltaMovement = back.scale(EJECTED_SPEED)
        thrown.setDefaultPickUpDelay()
        level.addFreshEntity(thrown)
    }

    /** Full where a picture waits to be taken out, and nothing where none does. */
    fun pictureWaitingSignal(): Int = if (items[RecorderSlots.PICTURE].isEmpty) 0 else FULL_SIGNAL

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        items = NonNullList.withSize(RecorderSlots.COUNT, ItemStack.EMPTY)
        ContainerHelper.loadAllItems(input, items)
        owner = input.read(OWNER_KEY, UUIDUtil.CODEC).orElse(null)
        aimYaw = input.getFloatOr(AIM_YAW_KEY, 0.0f)
        aimPitch = input.getFloatOr(AIM_PITCH_KEY, 0.0f)
        zoom = input.getFloatOr(ZOOM_KEY, WIDEST).coerceIn(NARROWEST, WIDEST)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        ContainerHelper.saveAllItems(output, items)
        owner?.let { output.store(OWNER_KEY, UUIDUtil.CODEC, it) }
        output.putFloat(AIM_YAW_KEY, aimYaw)
        output.putFloat(AIM_PITCH_KEY, aimPitch)
        output.putFloat(ZOOM_KEY, zoom)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    /** Only its aim and zoom, which a client draws it and looks through it by; what it holds is the server's. */
    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = CompoundTag().apply {
        putFloat(AIM_YAW_KEY, aimYaw)
        putFloat(AIM_PITCH_KEY, aimPitch)
        putFloat(ZOOM_KEY, zoom)
    }

    /** Nothing dropped: the slots go with the recorder, through its loot table, as a shulker box's do. */
    override fun preRemoveSideEffects(pos: BlockPos, state: BlockState) = Unit

    companion object {
        private val PICTURE_SIDE = intArrayOf(RecorderSlots.PICTURE)
        private val PLATE_SIDES = intArrayOf(RecorderSlots.PLATES)
        private const val OWNER_KEY = "owner"
        private const val AIM_YAW_KEY = "aim_yaw"
        private const val AIM_PITCH_KEY = "aim_pitch"
        private const val ZOOM_KEY = "zoom"

        /** Its widest view, the ordinary one, and its narrowest, a spyglass's, as a handheld recorder zooms. */
        const val WIDEST = 1.0f
        const val NARROWEST = 0.1f

        /** As far up or down as it tips on a tripod's head. */
        private const val MOST_PITCH = 90.0f
        private const val LEVEL = 0.0f
        private const val FULL_SIGNAL = 15
        private const val CLICK_PITCH = 1.2f
        private const val FLASH_COLOUR = 0xFFF4E0

        /** Where behind the block's middle a picture with nowhere to go is let out, and how hard. */
        private const val OUT_OF_THE_BACK = 0.6
        private const val EJECTED_HEIGHT = 0.2
        private const val EJECTED_SPEED = 0.15
    }
}

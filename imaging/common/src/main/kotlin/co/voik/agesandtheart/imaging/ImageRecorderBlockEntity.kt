package co.voik.agesandtheart.imaging

import co.voik.agesandtheart.age.word.WordNames
import co.voik.agesandtheart.content.Flash
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.NonNullList
import net.minecraft.core.UUIDUtil
import net.minecraft.core.particles.ColorParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.ContainerHelper
import net.minecraft.world.Containers
import net.minecraft.world.WorldlyContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.HopperMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * A set-down recorder's five slots, which only its automatic use reads: blank plates go in, from a hand or a hopper
 * above or beside it, and recorded images come out, by hand or to a hopper below, as a furnace's do. A pulse spends a
 * plate on a picture of what it faces, fires its flash, and puts the recorded image in a free slot; with no plate, or
 * nowhere to put the picture, it only clicks.
 */
class ImageRecorderBlockEntity(pos: BlockPos, state: BlockState) :
    BaseContainerBlockEntity(ImagingContent.IMAGE_RECORDER_ENTITY, pos, state), WorldlyContainer {

    private var items: NonNullList<ItemStack> = NonNullList.withSize(SLOTS, ItemStack.EMPTY)

    /** Whoever set it down, whose client is asked first for a photograph, and who stands as its pictures' photographer. */
    var owner: UUID? = null

    override fun getContainerSize(): Int = SLOTS

    override fun getItems(): NonNullList<ItemStack> = items

    override fun setItems(items: NonNullList<ItemStack>) {
        this.items = items
    }

    override fun getDefaultName(): Component = Component.translatable("block.agesandtheart_imaging.image_recorder")

    override fun createMenu(containerId: Int, inventory: Inventory): AbstractContainerMenu = HopperMenu(containerId, inventory, this)

    /** Plates and pictures, and nothing else, to whatever asks before putting a thing in. */
    override fun canPlaceItem(slot: Int, stack: ItemStack): Boolean = isAPlate(stack) || isAPicture(stack)

    override fun getSlotsForFace(side: Direction): IntArray = ALL_SLOTS

    override fun canPlaceItemThroughFace(slot: Int, stack: ItemStack, side: Direction?): Boolean = isAPlate(stack) && side != Direction.DOWN

    override fun canTakeItemThroughFace(slot: Int, stack: ItemStack, side: Direction): Boolean = isAPicture(stack) && side == Direction.DOWN

    /** Its pictures seen as it loads, as a carousel's slides are, so they never shrink while it stands where somebody goes. */
    override fun setLevel(level: Level) {
        super.setLevel(level)
        val server = (level as? ServerLevel)?.server ?: return
        items.forEach { stack -> stack.get(ImagingComponents.PICTURE)?.let { PicturesSeen.of(server).saw(it.id) } }
    }

    /**
     * A picture of what it faces, on a plate of its own, with its flash, drawn by a client ([AutomaticShots]); or a
     * click where it cannot take one.
     */
    fun shoot(level: ServerLevel) {
        val plate = items.indexOfFirst(::isAPlate)
        val roomForIt = items.indices.firstOrNull { items[it].isEmpty || (it == plate && items[it].count == 1) }
        if (plate < 0 || roomForIt == null) {
            level.playSound(null, blockPos, SoundEvents.DISPENSER_FAIL, SoundSource.BLOCKS, 1.0f, CLICK_PITCH)
            return
        }
        items[plate].shrink(1)
        val picture = Picture(UUID.randomUUID(), WordNames.placeName(level.dimension()), blockPos)
        items[roomForIt] = ItemStack(ImagingContent.RECORDED_IMAGE).apply { set(ImagingComponents.PICTURE, picture) }
        setChanged()
        level.updateNeighbourForOutputSignal(blockPos, blockState.block)
        PicturesSeen.of(level.server).saw(picture.id)

        val lens = ImageRecorderBlock.lensOf(blockState, blockPos)
        val pose = Shots.Pose(level.dimension(), lens.x, lens.y, lens.z, ImageRecorderBlock.yawOf(blockState), LEVEL)
        AutomaticShots.commission(level, picture.id, owner, pose)

        level.playSound(null, blockPos, ImagingContent.SHUTTER, SoundSource.BLOCKS, 1.0f, 1.0f)
        level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, FLASH_COLOUR), lens.x, lens.y, lens.z, 1, 0.0, 0.0, 0.0, 0.0)
        Flash.dazzleWhoSees(level, lens, FLASH_REACH, toward = pose.forward())
    }

    /** How many pictures it holds, three to each, so a comparator reads it filling. */
    fun picturesHeldSignal(): Int = items.count(::isAPicture) * SIGNAL_A_PICTURE

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        items = NonNullList.withSize(SLOTS, ItemStack.EMPTY)
        ContainerHelper.loadAllItems(input, items)
        owner = input.read(OWNER_KEY, UUIDUtil.CODEC).orElse(null)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        ContainerHelper.saveAllItems(output, items)
        owner?.let { output.store(OWNER_KEY, UUIDUtil.CODEC, it) }
    }

    override fun preRemoveSideEffects(pos: BlockPos, state: BlockState) {
        level?.let { Containers.dropContents(it, pos, this) }
    }

    private fun isAPlate(stack: ItemStack): Boolean = stack.`is`(ImagingContent.PHOTOGRAPHIC_PLATE)

    private fun isAPicture(stack: ItemStack): Boolean = stack.`is`(ImagingContent.RECORDED_IMAGE)

    private fun Shots.Pose.forward(): Vec3 = Vec3.directionFromRotation(pitch, yaw)

    companion object {
        const val SLOTS = 5
        private val ALL_SLOTS = IntArray(SLOTS) { it }
        private const val OWNER_KEY = "owner"
        private const val LEVEL = 0.0f
        private const val SIGNAL_A_PICTURE = 3
        private const val CLICK_PITCH = 1.2f
        private const val FLASH_COLOUR = 0xFFF4E0

        /** How far a set-down recorder's flash dazzles what faces it, nearer than a flash bomb's. */
        private const val FLASH_REACH = 6.0
    }
}

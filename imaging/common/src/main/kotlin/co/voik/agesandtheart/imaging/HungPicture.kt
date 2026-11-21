package co.voik.agesandtheart.imaging

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.decoration.HangingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * A recorded image hung on a wall as a painting is: the 16:9 picture a block wide, and where it is framed, its frame
 * making it a block square. It holds the recorded image itself, so taking it down gives back the same one, name and all.
 */
class HungPicture(type: EntityType<out HungPicture>, level: Level) : HangingEntity(type, level) {

    var image: ItemStack
        get() = entityData.get(IMAGE)
        private set(value) {
            entityData.set(IMAGE, value)
            recalculateBoundingBox()
        }

    /** The planks its frame is of, or null where it is the picture alone. */
    val frame: Block? get() = image.framedIn

    constructor(level: Level, pos: BlockPos, facing: Direction, image: ItemStack) : this(ImagingContent.HUNG_PICTURE, level) {
        this.pos = pos
        this.image = image.copyWithCount(1)
        setDirection(facing)
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(IMAGE, ItemStack.EMPTY)
    }

    /** A block wide and a sixteenth off the wall, as a one-block painting is: a block high in a frame, the picture's height without. */
    override fun calculateBoundingBox(pos: BlockPos, direction: Direction): AABB {
        val centre = Vec3.atCenterOf(pos).relative(direction, -OFF_THE_WALL)
        val isAlongX = direction.axis == Direction.Axis.X
        val height = if (frame != null) 1.0 else PICTURE_HEIGHT
        return AABB.ofSize(centre, if (isAlongX) THICKNESS else 1.0, height, if (isAlongX) 1.0 else THICKNESS)
    }

    /** A client learns the image after the entity, and only then how tall it hangs. */
    override fun onSyncedDataUpdated(accessor: EntityDataAccessor<*>) {
        super.onSyncedDataUpdated(accessor)
        if (accessor == IMAGE) recalculateBoundingBox()
    }

    override fun dropItem(level: ServerLevel, breaker: Entity?) {
        if (!level.gameRules.get(net.minecraft.world.level.gamerules.GameRules.ENTITY_DROPS)) return
        playSound(SoundEvents.PAINTING_BREAK, 1.0f, 1.0f)
        val takenDownInCreative = breaker is Player && breaker.hasInfiniteMaterials()
        if (!takenDownInCreative) spawnAtLocation(level, image.copy())
    }

    /** Seen as it loads, and every few minutes it stays loaded, so a picture on a wall never shrinks ([PictureSweep]). */
    override fun tick() {
        super.tick()
        val level = level() as? ServerLevel ?: return
        if (tickCount % TICKS_BETWEEN_SIGHTINGS == 1) image.get(ImagingComponents.PICTURE)?.let { PicturesSeen.of(level.server).saw(it.id) }
    }

    override fun playPlacementSound() {
        playSound(SoundEvents.PAINTING_PLACE, 1.0f, 1.0f)
    }

    override fun getPickResult(): ItemStack = image.copy()

    override fun addAdditionalSaveData(output: ValueOutput) {
        output.store(FACING_KEY, Direction.CODEC, direction)
        super.addAdditionalSaveData(output)
        if (!image.isEmpty) output.store(IMAGE_KEY, ItemStack.CODEC, image)
    }

    override fun readAdditionalSaveData(input: ValueInput) {
        setDirection(input.read(FACING_KEY, Direction.CODEC).orElse(Direction.SOUTH))
        super.readAdditionalSaveData(input)
        image = input.read(IMAGE_KEY, ItemStack.CODEC).orElse(ItemStack.EMPTY)
    }

    override fun getAddEntityPacket(serverEntity: ServerEntity): Packet<ClientGamePacketListener> =
        ClientboundAddEntityPacket(this, direction.get3DDataValue(), pos)

    override fun recreateFromPacket(packet: ClientboundAddEntityPacket) {
        super.recreateFromPacket(packet)
        setDirection(Direction.from3DDataValue(packet.data))
    }

    companion object {
        private val IMAGE: EntityDataAccessor<ItemStack> = SynchedEntityData.defineId(HungPicture::class.java, EntityDataSerializers.ITEM_STACK)

        /** A 16:9 picture a block wide. */
        const val PICTURE_HEIGHT = 9.0 / 16.0

        private const val FACING_KEY = "facing"
        private const val IMAGE_KEY = "image"
        private const val OFF_THE_WALL = 0.46875
        private const val THICKNESS = 0.0625
        private const val TICKS_BETWEEN_SIGHTINGS = 6000
    }
}

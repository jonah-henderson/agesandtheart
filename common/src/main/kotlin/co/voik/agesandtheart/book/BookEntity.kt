package co.voik.agesandtheart.book

import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.Vec3

/**
 * A book left behind at the place someone linked from (design §7.8).
 *
 * **It must never despawn.** Deliberate stranding is fair and is the point; being stranded because the
 * game tidied your way home away is not, and an `ItemEntity` would do exactly that after five minutes.
 * That is the whole reason this is an entity of its own rather than a dropped stack.
 *
 * Where it lands is the spectrum the design wants: solid ground and it is recoverable, a chasm or lava
 * and it is gone — the star fissure, reproduced as ordinary physics rather than a scripted set piece.
 */
class BookEntity(type: EntityType<out BookEntity>, level: Level) : Entity(type, level) {

    /** Synced so the client can draw the right cover and title without asking. */
    var book: ItemStack
        get() = entityData.get(BOOK)
        set(value) = entityData.set(BOOK, value)

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(BOOK, ItemStack.EMPTY)
    }

    override fun tick() {
        super.tick()
        if (!isNoGravity) {
            // Falls where it was dropped and stays there. Anything that eats it — lava, the void — is
            // the fiction working rather than a loss to guard against.
            deltaMovement = deltaMovement.subtract(0.0, GRAVITY, 0.0)
        }
        move(MoverType.SELF, deltaMovement)
        deltaMovement = deltaMovement.multiply(DRAG, if (onGround()) 0.0 else DRAG, DRAG)
    }

    /**
     * A book on the ground cannot be destroyed by being hit — only by the world eating it, which is the
     * fiction. Nothing here takes damage.
     */
    override fun hurtServer(level: ServerLevel, source: DamageSource, damage: Float): Boolean = false

    /** Picking it back up. The book is the item; the entity was only ever where it was resting. */
    override fun interact(player: Player, hand: InteractionHand, location: Vec3): InteractionResult {
        if (level().isClientSide) return InteractionResult.SUCCESS
        val held = book
        if (held.isEmpty) {
            discard()
            return InteractionResult.SUCCESS
        }
        if (!player.inventory.add(held.copy())) player.drop(held.copy(), false)
        discard()
        return InteractionResult.SUCCESS
    }

    /** Named for the book it holds — but an empty one is still a book, not "Air". */
    override fun getName(): Component = if (book.isEmpty) super.getName() else book.hoverName

    override fun isPickable(): Boolean = true

    /** Never, by design — see the class doc. */
    override fun shouldBeSaved(): Boolean = true

    override fun readAdditionalSaveData(input: ValueInput) {
        book = input.read(BOOK_KEY, ItemStack.CODEC).orElse(ItemStack.EMPTY)
    }

    override fun addAdditionalSaveData(output: ValueOutput) {
        if (!book.isEmpty) output.store(BOOK_KEY, ItemStack.CODEC, book)
    }

    companion object {
        private val BOOK: EntityDataAccessor<ItemStack> =
            SynchedEntityData.defineId(BookEntity::class.java, EntityDataSerializers.ITEM_STACK)

        private const val BOOK_KEY = "book"
        private const val GRAVITY = 0.04
        private const val DRAG = 0.98

        /** Leaves [stack] resting where [at] stood. */
        fun leaveBehind(level: ServerLevel, at: Vec3, stack: ItemStack): BookEntity? {
            val entity = AgeContent.BOOK_ENTITY.create(level, EntitySpawnReason.TRIGGERED) ?: return null
            entity.setPos(at.x, at.y, at.z)
            entity.book = stack.copy()
            level.addFreshEntity(entity)
            return entity
        }
    }
}

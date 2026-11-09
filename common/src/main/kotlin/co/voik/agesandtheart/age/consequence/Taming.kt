package co.voik.agesandtheart.age.consequence

import co.voik.agesandtheart.location
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Prediction
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.NeutralMob
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.DispensibleContainerItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.PotionItem
import net.minecraft.world.item.ThrowablePotionItem
import net.minecraft.world.level.Level
import java.util.UUID

/**
 * Taming a creature its Age wrote `tamable` ([Dispositions.Disposition.TAMABLE]): offered what it wants
 * ([Temptations]), it takes one in [TAMING_ODDS]. The owner is written on the creature as an entity tag, so it is saved
 * with it and needs no storage of its own; tamed, it keeps that owner wherever it goes ([TamedGoals.forTamed]).
 */
object Taming {

    fun ownerOf(mob: Mob): UUID? = mob.entityTags().firstNotNullOfOrNull { tag ->
        if (!tag.startsWith(OWNER_TAG)) return@firstNotNullOfOrNull null
        runCatching { UUID.fromString(tag.removePrefix(OWNER_TAG)) }.getOrNull()
    }

    /** [mob]'s owner, where they are in the same level. */
    fun ownerIn(mob: Mob): Player? = ownerOf(mob)?.let { mob.level().getPlayerByUUID(it) }

    fun isTamed(mob: Mob): Boolean = ownerOf(mob) != null

    fun isStaying(mob: Mob): Boolean = STAYING_TAG in mob.entityTags()

    /**
     * Whether a tamed [mob] may go for [target]: never its owner or its owner's other creatures, and otherwise only
     * what hit it, what is hitting its owner, and what its owner is hitting.
     */
    fun mayFight(mob: Mob, target: LivingEntity): Boolean {
        val ownerId = ownerOf(mob) ?: return true
        val isTheOwner = target.uuid == ownerId
        val isAFellow = target is Mob && ownerOf(target) == ownerId
        if (isTheOwner || isAFellow) return false
        val owner = mob.level().getPlayerByUUID(ownerId)
        val hitIt = target === mob.lastHurtByMob
        val fightsTheOwner = owner != null && (target === owner.lastHurtByMob || target === owner.lastHurtMob)
        return hitIt || fightsTheOwner
    }

    /**
     * A player using [entity] with [hand], on either side, before vanilla does — or null to leave it to vanilla.
     *
     * The client cannot know whether a taming will be tried, only whether the creature's kind is tempted by what is
     * held in its Age ([TemptationsHere]), and claims the use then so that the item is not used as well: a bucket
     * emptied or a potion drunk on top of the offer.
     */
    fun used(player: Player, level: Level, hand: InteractionHand, entity: Entity): InteractionResult? {
        val mob = entity as? Mob ?: return null
        if (player.isSpectator) return null
        val stack = player.getItemInHand(hand)
        if (level !is ServerLevel) return InteractionResult.SUCCESS.takeIf { TemptationsHere.claims(mob, stack) }
        if (ownerOf(mob) == player.uuid) return toldToStayOrFollow(player, hand, mob)
        val isWild = !isTamed(mob) && Dispositions.of(mob) == Dispositions.Disposition.TAMABLE
        if (!isWild || !Temptations.of(level.server).tempts(mob, stack)) return null
        offered(player, hand, stack, mob, level)
        return InteractionResult.SUCCESS
    }

    private fun offered(player: Player, hand: InteractionHand, stack: ItemStack, mob: Mob, level: ServerLevel) {
        handOver(player, hand, stack)
        val isTaken = level.random.nextInt(TAMING_ODDS) == 0
        if (isTaken) tamed(mob, player)
        show(level, mob, if (isTaken) ParticleTypes.HEART else ParticleTypes.SMOKE)
    }

    /** One of [stack] given up, and what held it given back: a bucket for a bucketful, a bottle for a potion. */
    private fun handOver(player: Player, hand: InteractionHand, stack: ItemStack) {
        if (player.hasInfiniteMaterials()) return
        val container = containerOf(stack)
        stack.shrink(1)
        if (container == null) return
        if (stack.isEmpty) player.setItemInHand(hand, container) else if (!player.inventory.add(container)) player.drop(container, false, Prediction.PREDICTED)
    }

    private fun containerOf(stack: ItemStack): ItemStack? {
        val item = stack.item
        val remainder = item.craftingRemainder?.create()
        return when {
            remainder != null && !remainder.isEmpty -> remainder
            item is DispensibleContainerItem -> ItemStack(Items.BUCKET)
            item is PotionItem && item !is ThrowablePotionItem -> ItemStack(Items.GLASS_BOTTLE)
            else -> null
        }
    }

    private fun tamed(mob: Mob, owner: Player) {
        mob.addTag(OWNER_TAG + owner.uuid)
        mob.target = null
        mob.brain.eraseMemory(MemoryModuleType.ATTACK_TARGET)
        mob.brain.eraseMemory(MemoryModuleType.ANGRY_AT)
        if (mob is NeutralMob) mob.stopBeingAngry()
        TamedGoals.forTamed(mob)
    }

    /** Sneaking with an empty hand, an owner tells their creature to stay where it is, or to come along again. */
    private fun toldToStayOrFollow(player: Player, hand: InteractionHand, mob: Mob): InteractionResult? {
        val isTheSign = hand == InteractionHand.MAIN_HAND && player.mainHandItem.isEmpty && player.isSecondaryUseActive
        if (!isTheSign) return null
        val nowStays = !isStaying(mob)
        if (nowStays) mob.addTag(STAYING_TAG) else mob.removeTag(STAYING_TAG)
        if (nowStays) TamedGoals.Steering.halt(mob)
        player.sendOverlayMessage(Component.translatable(if (nowStays) STAYS else FOLLOWS, mob.displayName))
        return InteractionResult.SUCCESS
    }

    private fun show(level: ServerLevel, mob: Mob, particle: ParticleOptions) {
        level.sendParticles(
            particle, mob.x, mob.y + mob.bbHeight * HEARTS_AT, mob.z, SHOWN,
            mob.bbWidth / 2.0, mob.bbHeight / 2.0, mob.bbWidth / 2.0, PARTICLE_SPEED,
        )
    }

    /** One in three, as a wolf takes a bone. */
    const val TAMING_ODDS = 3

    private const val OWNER_TAG = "agesandtheart.tamed_by:"
    private const val STAYING_TAG = "agesandtheart.staying"
    private const val STAYS = "creature.agesandtheart.stays"
    private const val FOLLOWS = "creature.agesandtheart.follows"
    private const val SHOWN = 7
    private const val HEARTS_AT = 0.8
    private const val PARTICLE_SPEED = 0.02
}

/**
 * What a client was last told tempts the creatures of the level it is in, so that it knows when to claim a use
 * ([Taming.used]). The server never reads it.
 */
object TemptationsHere {
    @Volatile
    private var told: Temptations = Temptations.NONE

    fun heard(payload: TemptationsHerePayload) {
        told = payload.temptations
    }

    fun forget() {
        told = Temptations.NONE
    }

    fun claims(mob: Mob, stack: ItemStack): Boolean = !stack.isEmpty && told.tempts(mob, stack)
}

/** What tempts each kind of creature an Age wrote tamable, sent to a player in it. */
data class TemptationsHerePayload(val temptations: Temptations) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<TemptationsHerePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<TemptationsHerePayload> = CustomPacketPayload.Type("temptations_here".location())

        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, TemptationsHerePayload> =
            Temptations.STREAM_CODEC.map(::TemptationsHerePayload, TemptationsHerePayload::temptations)

        /** The wants of the kinds [level] wrote tamable, and nothing anywhere else. */
        fun of(level: ServerLevel): TemptationsHerePayload {
            val table = Temptations.of(level.server)
            fun isTamableHere(kind: Identifier): Boolean {
                val type = BuiltInRegistries.ENTITY_TYPE.getOptional(kind).orElse(null) ?: return false
                return Dispositions.of(level, type) == Dispositions.Disposition.TAMABLE
            }
            return TemptationsHerePayload(Temptations(table.byKind.filterKeys(::isTamableHere)))
        }
    }
}

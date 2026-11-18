package co.voik.agesandtheart.content

import co.voik.agesandtheart.location
import net.minecraft.core.Direction
import net.minecraft.core.Holder
import net.minecraft.core.Position
import net.minecraft.core.particles.ColorParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.tags.TagKey
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.stats.Stats
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectCategory
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.NeutralMob
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ProjectileItem
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/** A flash bomb in hand: thrown as a snowball is, and from a dispenser. */
class FlashBombItem(properties: Properties) : Item(properties), ProjectileItem {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        val stack = player.getItemInHand(hand)
        val pitch = THROW_PITCH / (level.random.nextFloat() * THROW_PITCH_SPREAD + THROW_PITCH_LEAST)
        level.playSound(null, player.x, player.y, player.z, SoundEvents.SNOWBALL_THROW, SoundSource.NEUTRAL, THROW_VOLUME, pitch)
        if (level is ServerLevel) {
            Projectile.spawnProjectileFromRotation(::FlashBomb, level, stack, player, 0.0f, THROW_POWER, THROW_SPREAD)
        }
        player.awardStat(Stats.ITEM_USED.get(this))
        stack.consume(1, player)
        return InteractionResult.SUCCESS
    }

    override fun asProjectile(level: Level, position: Position, stack: ItemStack, direction: Direction): Projectile =
        FlashBomb(level, position.x(), position.y(), position.z(), stack)

    private companion object {
        const val THROW_POWER = 1.5f
        const val THROW_SPREAD = 1.0f
        const val THROW_VOLUME = 0.5f
        const val THROW_PITCH = 0.4f
        const val THROW_PITCH_SPREAD = 0.4f
        const val THROW_PITCH_LEAST = 0.8f
    }
}

/**
 * A thrown flash bomb, which bursts wherever it lands in a flash ([Flash]). It does no harm by itself, and is the
 * same thing however it hits.
 */
class FlashBomb : ThrowableItemProjectile {

    constructor(type: EntityType<out FlashBomb>, level: Level) : super(type, level)

    constructor(level: Level, owner: LivingEntity, stack: ItemStack) : super(AgeContent.FLASH_BOMB_ENTITY, owner, level, stack)

    constructor(level: Level, x: Double, y: Double, z: Double, stack: ItemStack) : super(AgeContent.FLASH_BOMB_ENTITY, x, y, z, level, stack)

    override fun getDefaultItem(): Item = AgeContent.FLASH_BOMB

    override fun onHit(hitResult: HitResult) {
        super.onHit(hitResult)
        val level = level() as? ServerLevel ?: return
        // Just off a face it struck, so the face itself does not stand between the flash and those who see it.
        val burst = if (hitResult is BlockHitResult) {
            hitResult.location.add(Vec3.atLowerCornerOf(hitResult.direction.unitVec3i).scale(OFF_THE_FACE))
        } else {
            hitResult.location
        }
        Flash.burst(level, burst)
        discard()
    }

    private companion object {
        const val OFF_THE_FACE = 0.25
    }
}

/**
 * A flash bomb's burst: a white flash and a crack, and every player and creature within [REACH] that sees it
 * [Dazzled], but for what is tagged `#agesandtheart:cannot_be_dazzled` (the wither and the ender dragon) and whoever
 * wears a helmet tagged `#agesandtheart:keeps_out_a_flash` (the deretheni helm, which covers the face). A player is
 * spared unless they were looking toward it, so one can be thrown down behind in a run, or looked away from.
 */
object Flash {

    private const val REACH = 10.0
    private const val SPARKS = 24
    private const val SPARK_SPREAD = 0.1
    private const val SPARK_SPEED = 0.25
    private const val BANG_VOLUME = 4.0f
    private const val WHITE = -1

    /** How nearly toward the burst a player must be looking to be dazzled by it: within about forty-five degrees. */
    private const val PLAYER_LOOKING_AT_IT = 0.7

    /** How nearly ahead of a thrown flash something must stand to see it: within about sixty degrees of its line. */
    private const val IN_FRONT_OF_IT = 0.5

    fun burst(level: ServerLevel, at: Vec3) {
        level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, WHITE), at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, 0.0)
        level.sendParticles(ParticleTypes.FIREWORK, at.x, at.y, at.z, SPARKS, SPARK_SPREAD, SPARK_SPREAD, SPARK_SPREAD, SPARK_SPEED)
        level.playSound(null, at.x, at.y, at.z, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.NEUTRAL, BANG_VOLUME, 1.0f)
        dazzleWhoSees(level, at, REACH)
    }

    /**
     * Dazzles everything within [reach] of a flash at [at] that sees it, by the burst's rules; where it [toward] is given,
     * the flash is thrown that way, as a lamp's is, and only what stands in front of it sees it.
     */
    fun dazzleWhoSees(level: ServerLevel, at: Vec3, reach: Double, toward: Vec3? = null) {
        val near = level.getEntitiesOfClass(LivingEntity::class.java, AABB.ofSize(at, reach * 2, reach * 2, reach * 2))
        for (body in near) {
            if (body.isSpectator || !body.isAlive) continue
            val isDazzleable = (body is Player || body is Mob) && !body.`is`(Dazzled.CANNOT_BE_DAZZLED) && !Dazzled.isShielded(body)
            val isAPlayerLookingAway = body is Player && !isLookingToward(body, at)
            val isBehindIt = toward != null && !isInFront(body, at, toward)
            val isDazzled = isDazzleable && !isAPlayerLookingAway && !isBehindIt && seesTheBurst(level, body, at, reach)
            if (isDazzled) body.addEffect(MobEffectInstance(Dazzled.EFFECT, Dazzled.LASTS_FOR_TICKS))
        }
    }

    private fun isInFront(body: LivingEntity, at: Vec3, toward: Vec3): Boolean {
        val toTheEyes = body.eyePosition.subtract(at)
        if (toTheEyes.lengthSqr() < 1.0e-6) return true
        return toward.normalize().dot(toTheEyes.normalize()) >= IN_FRONT_OF_IT
    }

    private fun isLookingToward(body: LivingEntity, at: Vec3): Boolean {
        val toTheBurst = at.subtract(body.eyePosition)
        if (toTheBurst.lengthSqr() < 1.0e-6) return true
        return body.getViewVector(1.0f).dot(toTheBurst.normalize()) >= PLAYER_LOOKING_AT_IT
    }

    /** Whether the burst at [at] is within reach of [body]'s eyes with nothing in the way. */
    private fun seesTheBurst(level: ServerLevel, body: LivingEntity, at: Vec3, reach: Double): Boolean {
        val eye = body.eyePosition
        if (eye.distanceTo(at) > reach) return false
        val sightLine = level.clip(ClipContext(eye, at, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, body))
        return sightLine.type == HitResult.Type.MISS
    }
}

/**
 * Dazzled by a flash, for ten seconds. A player's view whites out and clears (`client/DazzledView`). A creature loses
 * what it was after and cannot take a player for a target at all, however near, nor turn on one that hits it
 * (`mixin/CanAttackMixin`); anything else it finds no further off than a tenth of its usual reach. Vanilla's blindness
 * does nothing to how a creature finds its target, which is why this is its own effect.
 */
object Dazzled {
    val EFFECT_ID: Identifier = "dazzled".location()

    /** What a flash does not dazzle: the wither and the ender dragon, and whatever a pack adds. */
    val CANNOT_BE_DAZZLED: TagKey<EntityType<*>> = TagKey.create(Registries.ENTITY_TYPE, "cannot_be_dazzled".location())

    const val LASTS_FOR_TICKS = 200

    val EFFECT_INSTANCE: MobEffect = DazzledEffect()
        .addAttributeModifier(Attributes.FOLLOW_RANGE, EFFECT_ID, -0.9, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)

    val EFFECT: Holder<MobEffect> by lazy { BuiltInRegistries.MOB_EFFECT.wrapAsHolder(EFFECT_INSTANCE) }

    /** What a helmet keeps out a flash with. */
    val KEEPS_OUT_A_FLASH: TagKey<Item> = TagKey.create(Registries.ITEM, "keeps_out_a_flash".location())

    /** Whether [body] wears a helmet that keeps a flash out of its eyes. */
    fun isShielded(body: LivingEntity): Boolean = body.getItemBySlot(EquipmentSlot.HEAD).`is`(KEEPS_OUT_A_FLASH)

    /** Whether [attacker] is dazzled and so cannot see [target], a player. */
    @JvmStatic
    fun hidesFrom(attacker: LivingEntity, target: LivingEntity?): Boolean =
        target is Player && attacker is Mob && attacker.hasEffect(EFFECT)

    private class DazzledEffect : MobEffect(MobEffectCategory.HARMFUL, COLOUR) {
        override fun onEffectStarted(mob: LivingEntity, amplifier: Int) {
            if (mob !is Mob) return
            mob.target = null
            mob.brain.eraseMemory(MemoryModuleType.ATTACK_TARGET)
            if (mob is NeutralMob) mob.stopBeingAngry()
        }
    }

    /** A flash's white, a little warm. */
    private const val COLOUR = 0xFFF4D6
}

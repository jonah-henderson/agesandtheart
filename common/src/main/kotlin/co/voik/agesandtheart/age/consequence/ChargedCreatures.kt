package co.voik.agesandtheart.age.consequence

import co.voik.agesandtheart.location
import co.voik.agesandtheart.platform.Services
import io.netty.buffer.ByteBuf
import net.minecraft.core.UUIDUtil
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Creatures an Age wrote `charged`. A creeper is powered, as lightning powers one. Anything else is marked, drawn in a
 * charged creeper's swirl, and bursts when it dies with an ordinary creeper's blast at its size: a colossal cow's is
 * wider than the wither's. Its drops are kept out of its own blast and flung by it, harder the wider it was.
 *
 * A blast goes off where the body lies as its death ends, so it falls over first and is not flung by its own blast,
 * and a herd caught in one goes up in a ripple. Blocks break only where `mobGriefing` lets them, as a creeper's do.
 */
object ChargedCreatures {

    /** Charges [mob], and tells whoever can see it so it is drawn charged. */
    fun charge(mob: Mob) {
        if (mob is Creeper) {
            mob.entityData.set(Creeper.DATA_IS_POWERED, true)
            return
        }
        val isNewlyCharged = mob.addTag(CHARGED_TAG)
        val level = mob.level() as? ServerLevel ?: return
        if (isNewlyCharged) level.chunkSource.chunkMap.getPlayers(mob.chunkPosition(), false).forEach { tell(it, mob) }
    }

    fun isCharged(entity: Entity): Boolean = CHARGED_TAG in entity.entityTags()

    /** [player] beginning to see [entity], told it is charged where it is. */
    fun seen(player: ServerPlayer, entity: Entity) {
        if (isCharged(entity)) tell(player, entity)
    }

    private fun tell(player: ServerPlayer, entity: Entity) = Services.NETWORK.sendToPlayer(player, ChargedCreaturePayload(entity.uuid))

    /** [victim] dying: a charged one other than a creeper, whose own blast is its fuse's, bursts as its death ends. */
    fun died(victim: LivingEntity) {
        val level = victim.level() as? ServerLevel ?: return
        if (victim is Creeper || !isCharged(victim)) return
        val size = WrittenCreatures.dropsOf(victim) ?: AS_IT_IS
        bursting += Burst(level, victim, blastRadiusAt(size), level.gameTime)
    }

    /** The blasts of the creatures whose deaths have run their course, and later those of whatever they kill. */
    fun burst() {
        val due = bursting.filter { it.level.gameTime - it.diedAt >= LivingEntity.DEATH_DURATION }
        bursting.removeAll(due)
        due.filter { !it.level.isClientSide }.forEach(::burst)
    }

    fun serverStopped() {
        bursting.clear()
    }

    private fun burst(burst: Burst) {
        val (level, body, radius, diedAt) = burst
        val at = body.position()
        val droppedSince = level.gameTime - diedAt + DROPS_AGE
        val reach = AABB.ofSize(at, DROPS_REACH * 2, DROPS_REACH * 2, DROPS_REACH * 2)
        val drops = level.getEntitiesOfClass(ItemEntity::class.java, reach) { it.age <= droppedSince && !it.isPermanentlyInvulnerable }
        drops.forEach { it.isPermanentlyInvulnerable = true }
        level.explode(null, at.x, at.y, at.z, radius, Level.ExplosionInteraction.MOB)
        drops.forEach { drop ->
            drop.isPermanentlyInvulnerable = false
            drop.deltaMovement = flung(radius, level.random)
        }
    }

    /** How fast a drop leaves a blast of [radius]: outward, at an angle of its own, and up. */
    private fun flung(radius: Float, random: RandomSource): Vec3 {
        val angle = random.nextDouble() * FULL_TURN
        val outward = radius * FLUNG_OUTWARD * (HALF + random.nextDouble() * HALF)
        val upward = radius * FLUNG_UPWARD * (HALF + random.nextDouble() * HALF)
        return Vec3(cos(angle) * outward, upward, sin(angle) * outward)
    }

    /** The radius of a creeper's blast from a creature [size] times its own size, as its drops scale. */
    fun blastRadiusAt(size: Double): Float = (CREEPER_BLAST * size).toFloat()

    /** A creeper's own blast radius at [size] times its own size, which its charge then doubles as vanilla's does. */
    fun creeperRadiusAt(size: Double): Int = (CREEPER_BLAST * size).roundToInt().coerceAtLeast(1)

    private data class Burst(val level: ServerLevel, val body: LivingEntity, val radius: Float, val diedAt: Long)

    private val bursting = mutableListOf<Burst>()

    private const val CHARGED_TAG = "agesandtheart.charged"

    /** A creeper's blast radius, uncharged. */
    private const val CREEPER_BLAST = 3.0
    private const val AS_IT_IS = 1.0

    /** How far from the body a creature's drops are found, and how many ticks older than its death they may be. */
    private const val DROPS_REACH = 3.0
    private const val DROPS_AGE = 2

    /** A drop's speed out of a blast and up, in blocks a tick for each block of its radius. */
    private const val FLUNG_OUTWARD = 0.12
    private const val FLUNG_UPWARD = 0.15
    private const val HALF = 0.5
    private const val FULL_TURN = 2 * Math.PI
}

/** A creature [creature] is charged, for its client to draw it so. */
data class ChargedCreaturePayload(val creature: UUID) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<ChargedCreaturePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<ChargedCreaturePayload> = CustomPacketPayload.Type("charged_creature".location())

        val STREAM_CODEC: StreamCodec<ByteBuf, ChargedCreaturePayload> =
            UUIDUtil.STREAM_CODEC.map(::ChargedCreaturePayload, ChargedCreaturePayload::creature)
    }
}

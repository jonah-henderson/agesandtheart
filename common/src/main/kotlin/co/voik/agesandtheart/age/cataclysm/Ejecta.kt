package co.voik.agesandtheart.age.cataclysm

import co.voik.agesandtheart.client.Impacts
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.location
import co.voik.agesandtheart.worldgen.feature.ImpactCrater
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.projectile.ThrowableProjectile
import net.minecraft.world.level.Level
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A burning fragment of what the asteroid threw up, coming back down near a player (design §5.3 "The asteroid"):
 * a lump of molten rock falling steeply out of the impact's quarter, trailing fire and smoke, that goes off where it
 * lands and leaves a small crater floored with hot rock.
 */
class Ejecta(type: EntityType<out Ejecta>, level: Level) : ThrowableProjectile(type, level) {

    override fun defineSynchedData(builder: SynchedEntityData.Builder) = Unit

    override fun getDefaultGravity(): Double = ALMOST_NONE

    override fun fireImmune(): Boolean = true

    /** Seen across its whole fall, which its hitbox would not earn it. */
    override fun shouldRenderAtSqrDistance(distance: Double): Boolean = distance < SEEN_FROM * SEEN_FROM

    /** Part of an end a restart forgets, so not kept past one either. */
    override fun shouldBeSaved(): Boolean = false

    /** Never by another fragment, or a close pair would go off in mid-air. */
    override fun canHitEntity(target: Entity): Boolean = target !is Ejecta && super.canHitEntity(target)

    override fun onHitEntity(hit: EntityHitResult) {
        super.onHitEntity(hit)
        val onTheServer = level() as? ServerLevel ?: return
        hit.entity.hurtServer(onTheServer, damageSources().thrown(this, getOwner()), STRUCK)
    }

    override fun onHit(hit: HitResult) {
        super.onHit(hit)
        val level = level()
        if (level is ServerLevel) {
            strike(level)
            discard()
            return
        }
        // Felt on the client, once: it goes on hitting there until the server's word that it is gone arrives.
        if (hasLanded) return
        hasLanded = true
        Impacts.landedNear(hit.location)
    }

    private var hasLanded = false

    override fun tick() {
        super.tick()
        val level = level()
        if (level is ServerLevel) {
            if (tickCount > A_WHOLE_FALL) discard()
            return
        }
        shedFireAndSmoke(level)
    }

    /** Fire and smoke along the stretch it just crossed, the smoke left hanging in billows where it passed. */
    private fun shedFireAndSmoke(level: Level) {
        val from = Vec3(xOld, yOld, zOld)
        val crossed = position().subtract(from)
        fun jitter() = (random.nextDouble() - HALF) * SHED_SPREAD
        fun shed(particle: SimpleParticleType, count: Int, rising: Double) {
            repeat(count) {
                val at = from.add(crossed.scale(random.nextDouble()))
                level.addAlwaysVisibleParticle(particle, true, at.x + jitter(), at.y + jitter(), at.z + jitter(), NO_DRIFT, rising, NO_DRIFT)
            }
        }
        shed(ParticleTypes.FLAME, FLAMES_A_TICK, NO_DRIFT)
        shed(ParticleTypes.LARGE_SMOKE, SMOKE_A_TICK, NO_DRIFT)
        shed(ParticleTypes.CAMPFIRE_COSY_SMOKE, BILLOWS_A_TICK, SMOKE_RISES)
    }

    /** The blast, then the bowl, then its floor set burning. */
    private fun strike(level: ServerLevel) {
        level.explode(this, x, y, z, BLAST, false, Level.ExplosionInteraction.NONE)
        val middle = blockPosition()
        ImpactCrater.strike(level, level.random, middle, CRATER_REACH, NO_SHARD, Unkept)
        layHotRock(level, middle)
    }

    private fun layHotRock(level: ServerLevel, middle: BlockPos) {
        val random = level.random
        for (awayX in -CRATER_REACH..CRATER_REACH) {
            for (awayZ in -CRATER_REACH..CRATER_REACH) {
                val away = sqrt((awayX * awayX + awayZ * awayZ).toDouble())
                if (away > CRATER_REACH * HOT_REACH) continue
                val column = BlockPos(middle.x + awayX, middle.y, middle.z + awayZ)
                if (!level.isLoaded(column)) continue
                val above = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column)
                val floor = above.below()
                val isSolidGround = level.getBlockState(floor).isSolidRender && level.getFluidState(above).isEmpty
                if (!isSolidGround) continue
                val roll = random.nextFloat()
                val hot = when {
                    roll < MAGMA_SHARE -> Blocks.MAGMA_BLOCK
                    roll < MAGMA_SHARE + BASALT_SHARE -> Blocks.BASALT
                    roll < MAGMA_SHARE + BASALT_SHARE + BLACKSTONE_SHARE -> Blocks.BLACKSTONE
                    else -> null
                }
                if (hot != null) level.setBlock(floor, hot.defaultBlockState(), Block.UPDATE_ALL)
                val catches = random.nextInt(FIRE_ONE_IN) == 0 && level.getBlockState(above).isAir &&
                    BaseFireBlock.canBePlacedAt(level, above, Direction.UP)
                if (catches) level.setBlock(above, BaseFireBlock.getState(level, above), Block.UPDATE_ALL)
            }
        }
    }

    /** A crater cut without keeping anything it broke: there is nobody left to pick it up. */
    private object Unkept : ImpactCrater.Carving {
        override val flags: Int = Block.UPDATE_ALL

        override fun set(level: WorldGenLevel, at: BlockPos, state: BlockState) {
            level.setBlock(at, state, flags)
        }
    }

    companion object {
        val ID: Identifier = "ejecta".location()

        /**
         * One fragment, aimed at the ground somewhere round [player] and coming in steeply out of the quarter the
         * asteroid struck on, [bearingDegrees] clockwise from north. None where nowhere on its way in is ticking.
         */
        fun throwNear(level: ServerLevel, player: ServerPlayer, bearingDegrees: Float) {
            val random = level.random
            val round = random.nextDouble() * FULL_TURN
            val away = NEAREST + sqrt(random.nextDouble()) * (FURTHEST - NEAREST)
            val aimedAt = BlockPos.containing(player.x + cos(round) * away, player.y, player.z + sin(round) * away)
            if (!level.isLoaded(aimedAt)) return
            val ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, aimedAt)
            val landing = Vec3.atBottomCenterOf(ground)

            val bearing = Math.toRadians(bearingDegrees.toDouble()) + (random.nextDouble() - HALF) * FANS_OUT
            val towardTheImpact = Vec3(sin(bearing), 0.0, -cos(bearing))
            val steepness = Math.toRadians(SHALLOWEST + random.nextDouble() * (STEEPEST - SHALLOWEST))
            val backAlongItsPath = towardTheImpact.scale(cos(steepness)).add(0.0, sin(steepness), 0.0)
            val from = FALLS_FROM.map { landing.add(backAlongItsPath.scale(it)) }
                .firstOrNull { level.isPositionEntityTicking(BlockPos.containing(it)) } ?: return

            val fragment = Ejecta(AgeContent.EJECTA, level)
            fragment.setPos(from.x, from.y, from.z)
            fragment.setDeltaMovement(landing.subtract(from).normalize().scale(SPEED))
            level.addFreshEntity(fragment)
        }

        /** How far from a player one comes down, in blocks: never on top of them, and well within sight. */
        private const val NEAREST = 14.0
        private const val FURTHEST = 72.0

        /** How far back along its path it is set going, the longest that is ticking first. */
        private val FALLS_FROM = listOf(150.0, 100.0, 60.0)

        /** How steeply it comes in, in degrees, and how far off the impact's own bearing, in radians. */
        private const val SHALLOWEST = 35.0
        private const val STEEPEST = 65.0
        private const val FANS_OUT = 0.6

        /** In blocks a tick: a long fall over in a couple of seconds, and still bright as it lands. */
        private const val SPEED = 4.0

        private const val ALMOST_NONE = 0.005
        private const val STRUCK = 10.0f

        /** A good deal past TNT. */
        private const val BLAST = 6.0f
        private const val CRATER_REACH = 6
        private const val NO_SHARD = 0.0f

        /** How much of the bowl is floored hot, as a share of its reach, and with what. */
        private const val HOT_REACH = 0.85
        private const val MAGMA_SHARE = 0.4f
        private const val BASALT_SHARE = 0.25f
        private const val BLACKSTONE_SHARE = 0.15f
        private const val FIRE_ONE_IN = 4

        private const val FLAMES_A_TICK = 6
        private const val SMOKE_A_TICK = 4
        private const val BILLOWS_A_TICK = 6
        private const val SHED_SPREAD = 2.0
        private const val SMOKE_RISES = 0.02
        private const val NO_DRIFT = 0.0

        private const val SEEN_FROM = 320.0

        /** A fall that has not landed in this many ticks was stopped somewhere, and is simply gone. */
        private const val A_WHOLE_FALL = 200

        private const val HALF = 0.5
        private const val FULL_TURN = 2.0 * PI
    }
}

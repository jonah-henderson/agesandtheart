package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.projectile.ThrowableProjectile
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A gobbet of molten rock thrown off a [VolcanicBomb]'s impact, which becomes a lava source where it lands.
 *
 * **This exists so nothing has to reason about a crater on the tick it is made** (Jonah, 2026-09-06). The
 * bomb used to look for the floor under itself and pour lava into it, which meant asking where the ground
 * was in the middle of an explosion that had just moved it — and got it wrong the obvious way, hanging a
 * source block in the air over the hole it had dug. A gobbet has no opinion about any of that. It falls,
 * and whatever it lands on is by definition the floor: the crater settles itself, and the pool it ends up
 * with is the shape of the hole rather than a guess at it.
 *
 * Being thrown rather than dropped is what spreads them. They come off the burst outward and upward, so a
 * hit on a slope splashes downhill and a hit in a crater falls back into it.
 */
class LavaDroplet(type: EntityType<out LavaDroplet>, level: Level) : ThrowableProjectile(type, level) {

    /** Nothing about a gobbet is watched by the client but its position, which vanilla already sends. */
    override fun defineSynchedData(builder: SynchedEntityData.Builder) = Unit

    override fun onHit(hit: HitResult) {
        super.onHit(hit)
        val level = level()
        if (level is ServerLevel) settle(level, hit)
        discard()
    }

    /**
     * Turn to lava where this came to rest.
     *
     * Laid in the space *this side* of the face it struck, which is the block it would occupy — putting it
     * inside the block it hit would replace whatever it landed on, so a gobbet would eat the ground rather
     * than pool on it.
     */
    private fun settle(level: ServerLevel, hit: HitResult) {
        val at = if (hit is BlockHitResult) hit.blockPos.relative(hit.direction) else blockPosition()
        val standing = level.getBlockState(at)
        // Somewhere already molten, or somewhere with no room: either way this one is simply spent. Both
        // matter — without them a volcano would keep stacking lava into ground that had already flooded.
        if (standing.blocksMotion() || standing.`is`(Blocks.LAVA)) return
        level.setBlockAndUpdate(at, Blocks.LAVA.defaultBlockState())
        level.playSound(null, at, SoundEvents.LAVA_POP, SoundSource.BLOCKS)
    }

    override fun tick() {
        super.tick()
        if (!level().isClientSide) return
        level().addParticle(ParticleTypes.LAVA, x, y, z, NO_DRIFT, NO_DRIFT, NO_DRIFT)
    }

    /** Heavier than the bomb that threw it: a gobbet should drop into the crater, not sail out of it. */
    override fun getDefaultGravity(): Double = HEAVY

    companion object {
        private const val HEAVY = 0.07
        private const val NO_DRIFT = 0.0
        private const val HALF = 0.5
        private const val FULL_TURN = 2.0 * PI

        /**
         * Throw [count] of them off a burst at [from].
         *
         * Upward and outward at a low speed, so they arc a handful of blocks and come down inside the
         * crater the explosion has just finished making.
         */
        fun spatteredFrom(level: ServerLevel, from: BlockPos, count: Int) {
            val random = level.random
            repeat(count) {
                val droplet = LavaDroplet(AgeContent.LAVA_DROPLET, level)
                droplet.setPos(from.x + HALF, from.y + HALF, from.z + HALF)
                val bearing = random.nextDouble() * FULL_TURN
                val across = random.nextDouble() * SIDEWAYS
                droplet.setDeltaMovement(
                    across * cos(bearing),
                    UPWARD + random.nextDouble() * UPWARD_SPREAD,
                    across * sin(bearing),
                )
                level.addFreshEntity(droplet)
            }
        }

        private const val SIDEWAYS = 0.32
        private const val UPWARD = 0.18
        private const val UPWARD_SPREAD = 0.28
    }
}

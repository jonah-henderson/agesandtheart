package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
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
 * and whatever it comes down *onto* is by definition the floor: the crater settles itself, and the pool it
 * ends up with is the shape of the hole rather than a guess at it.
 *
 * *Onto* is load-bearing and was learned the hard way underground — see [settle].
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
     * Turn to lava where this came to rest — **which has to be a floor, and has to be one it fell onto**.
     *
     * Laid in the space *this side* of the face it struck, which is the block it would occupy — putting it
     * inside the block it hit would replace whatever it landed on, so a gobbet would eat the ground rather
     * than pool on it.
     *
     * **Two refusals, both walked on 2026-09-11 in a magma chamber.** A bomb thrown up a chamber bursts
     * against the roof, and its gobbets went straight up into that roof and stuck there — lava hanging off
     * a ceiling and pouring down it, which reads as a fault rather than as an eruption (Jonah). So:
     *
     * - **nothing settles while it is still rising.** A gobbet on its way up has not landed on anything; it
     *   has run into something.
     * - **and only the top of a block counts as a floor.** A wall or a ceiling is not somewhere lava pools,
     *   and a gobbet that clips one is simply spent. Lava still runs *down* a wall — from the ledge above
     *   it that a gobbet did land on, which is the reading Jonah kept deliberately.
     *
     * The two are not the same test and both are wanted: a gobbet falling into a sloped crack can strike a
     * side face on the way down, and one thrown flat can strike a floor while still rising a little.
     */
    private fun settle(level: ServerLevel, hit: HitResult) {
        if (deltaMovement.y > FALLING) return
        if (hit !is BlockHitResult || hit.direction != Direction.UP) return
        val at = hit.blockPos.relative(hit.direction)
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
        /**
         * At rest or on the way down. Zero rather than a tolerance: a gobbet's whole arc is drawn by
         * gravity, so anything with upward motion left in it has not finished rising.
         */
        private const val FALLING = 0.0

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

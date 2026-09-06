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
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult

/**
 * A lump of molten rock thrown out of a volcano (design §7.1.2).
 *
 * It arcs, so it is a thrown projectile rather than a falling block — vanilla's falling block only ever
 * goes straight down. Where it lands it cratates the ground and pools a little lava in the hole.
 *
 * **A bomb that lands in lava does neither.** That is the bound on the whole mechanic rather than a
 * special case: ground that has already flooded stops accumulating, so a volcano deepens its own pool and
 * then stops enlarging it, and the Age does not creep toward being made of lava. It also means the safest
 * place to stand is the one that has already been hit, which is a lesson worth learning the hard way.
 */
class VolcanicBomb(type: EntityType<out VolcanicBomb>, level: Level) : ThrowableProjectile(type, level) {

    /**
     * How hard this one goes off, as a share of what a full vent could throw.
     *
     * Carried on the bomb rather than looked up on landing, because the mass that threw it may be mined out
     * or plugged while it is still in the air — what is in flight was already paid for.
     */
    var force: Double = LEAST

    /** Nothing about a bomb is watched by the client but its position, which vanilla already sends. */
    override fun defineSynchedData(builder: SynchedEntityData.Builder) = Unit

    override fun onHitEntity(hit: EntityHitResult) {
        super.onHitEntity(hit)
        hit.entity.hurt(damageSources().thrown(this, getOwner()), CONTACT_DAMAGE)
    }

    override fun onHit(hit: HitResult) {
        super.onHit(hit)
        val level = level()
        if (level !is ServerLevel) return
        if (quenched(level)) {
            level.playSound(null, blockPosition(), SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS)
            discard()
            return
        }
        burst(level, hit)
        discard()
    }

    /**
     * Whether this landed somewhere already molten.
     *
     * Asked of the block it is *in* rather than the one it struck: a bomb arriving in a lava pool never
     * reaches a surface to hit, and one that clips a rim above the lava has not really landed in it.
     */
    private fun quenched(level: ServerLevel): Boolean {
        // **Only on the way down.** A bomb erupting through the last of its own lava is still being thrown,
        // and quenching it there would have a volcano put out every shot it fired.
        if (deltaMovement.y > FALLING) return false
        return level.getBlockState(blockPosition()).`is`(Blocks.LAVA)
    }

    private fun burst(level: ServerLevel, hit: HitResult) {
        val at = if (hit is BlockHitResult) hit.blockPos else blockPosition()
        level.explode(this, x, y, z, strength(), true, Level.ExplosionInteraction.TNT)
        pool(level, at)
    }

    /**
     * A little lava in the hole it just made.
     *
     * Poured into the floor of the crater rather than at the point of impact, so it settles where the
     * explosion left room instead of hanging in the air above it.
     */
    private fun pool(level: ServerLevel, at: BlockPos) {
        val floor = (0..POOL_DEPTH).map { at.below(it) }.firstOrNull { level.getBlockState(it).isAir }
            ?: return
        level.setBlockAndUpdate(floor, Blocks.LAVA.defaultBlockState())
    }

    /** Creeper-sized at the least, and a good deal past TNT at a full vent. */
    private fun strength(): Float =
        (LIKE_A_CREEPER + (AT_FULL_VENT - LIKE_A_CREEPER) * force).toFloat()

    override fun tick() {
        super.tick()
        if (!level().isClientSide) return
        level().addParticle(ParticleTypes.LAVA, x, y, z, NO_DRIFT, NO_DRIFT, NO_DRIFT)
    }

    override fun getDefaultGravity(): Double = HEAVY

    override fun addAdditionalSaveData(output: ValueOutput) {
        super.addAdditionalSaveData(output)
        output.putDouble(FORCE_KEY, force)
    }

    override fun readAdditionalSaveData(input: ValueInput) {
        super.readAdditionalSaveData(input)
        force = input.getDoubleOr(FORCE_KEY, LEAST)
    }

    companion object {
        /** Thrown, so it is a hard knock on contact and the explosion does the rest. */
        private const val CONTACT_DAMAGE = 4.0f

        private const val LIKE_A_CREEPER = 3.0
        private const val AT_FULL_VENT = 7.0

        private const val POOL_DEPTH = 3

        /**
         * A snowball's, and light on purpose.
         *
         * **Readability beats realism here.** Rock ought to fall hard, and at four times this it did — but
         * a fast bomb is one you cannot look up and track, and the whole counterplay is seeing where a
         * shot will land and moving. A slow arc also reads as *mass*, oddly, because the eye takes a heavy
         * object's flight as slow and floating.
         */
        private const val HEAVY = 0.03

        private const val FALLING = 0.0
        private const val NO_DRIFT = 0.0
        private const val LEAST = 0.0

        private const val FORCE_KEY = "force"

        /** Thrown from a vent, with enough spread that a volcano does not shell one spot. */
        fun thrownFrom(level: ServerLevel, from: BlockPos, force: Double) {
            val bomb = VolcanicBomb(AgeContent.VOLCANIC_BOMB, level)
            bomb.force = force
            bomb.setPos(from.x + HALF, from.y + HALF, from.z + HALF)
            val random = level.random
            bomb.setDeltaMovement(
                (random.nextDouble() - HALF) * SPREAD,
                UPWARD + random.nextDouble() * UPWARD_SPREAD,
                (random.nextDouble() - HALF) * SPREAD,
            )
            level.addFreshEntity(bomb)
            level.playSound(null, from, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS)
        }

        private const val HALF = 0.5
        private const val SPREAD = 1.2
        private const val UPWARD = 0.9
        private const val UPWARD_SPREAD = 0.5
    }
}

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
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A lump of molten rock thrown out of a volcano (design §7.1.2).
 *
 * It arcs, so it is a thrown projectile rather than a falling block — vanilla's falling block only ever
 * goes straight down. Where it lands it craters the ground and pools a little lava in the hole.
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
     * A little lava in the floor of the hole it just made.
     *
     * **Poured from the bottom of the crater upward, never at the point of impact.** A bomb goes off at
     * head height on whatever it clipped, so laying lava where it struck hangs a source block in the air
     * over the hole it just dug. Falling to the floor first and filling from there is what makes the pool
     * sit in the crater, and the depth is drawn per bomb so a bombarded slope ends up dappled with pools
     * of different sizes rather than tiled with identical ones.
     *
     * A bomb that finds no floor within reach — one intercepted high over a valley — pools nothing.
     */
    private fun pool(level: ServerLevel, at: BlockPos) {
        val floor = craterFloorUnder(level, at) ?: return
        val layers = LEAST_LAYERS + level.random.nextInt(MOST_LAYERS - LEAST_LAYERS + ONE)
        flood(level, floor, ceiling = floor.y + layers - ONE)
    }

    /** The lowest open block under the burst, or null if there is no ground under it worth pooling on. */
    private fun craterFloorUnder(level: ServerLevel, at: BlockPos): BlockPos? {
        var here = if (level.getBlockState(at).blocksMotion()) at.above() else at
        for (drop in 0..<DEEPEST_CRATER) {
            if (level.getBlockState(here.below()).blocksMotion()) return here
            here = here.below()
        }
        return null
    }

    /** Outward along the crater floor, then up — the same spread a vent uses, at a crater's scale. */
    private fun flood(level: ServerLevel, floor: BlockPos, ceiling: Int) {
        val reach = strength().toDouble()
        val seen = HashSet<BlockPos>()
        var frontier = listOf(floor)
        var laid = 0
        while (frontier.isNotEmpty() && laid < MOST_POOLED) {
            val next = mutableListOf<BlockPos>()
            for (position in frontier) {
                if (laid >= MOST_POOLED) break
                if (!seen.add(position)) continue
                if (position.y > ceiling) continue
                if (!withinReach(position, floor, reach)) continue
                val state = level.getBlockState(position)
                if (state.blocksMotion()) continue
                if (!state.`is`(Blocks.LAVA)) {
                    level.setBlockAndUpdate(position, Blocks.LAVA.defaultBlockState())
                    laid++
                }
                Direction.Plane.HORIZONTAL.forEach { way -> next += position.relative(way) }
                next += position.above()
            }
            frontier = next
        }
    }

    private fun withinReach(position: BlockPos, floor: BlockPos, reach: Double): Boolean {
        val spreadX = (position.x - floor.x).toDouble()
        val spreadZ = (position.z - floor.z).toDouble()
        return spreadX * spreadX + spreadZ * spreadZ <= reach * reach
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

        private const val LEAST_LAYERS = 1
        private const val MOST_LAYERS = 3
        private const val DEEPEST_CRATER = 8
        private const val MOST_POOLED = 96

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
        private const val ONE = 1

        private const val FORCE_KEY = "force"

        /**
         * Thrown from a vent, on a bearing, a climb and a speed all drawn separately.
         *
         * **Drawing the shot rather than its three components is what gives a volcano its spread.**
         * Perturbing each axis a little around one nominal throw makes every bomb land in much the same
         * ring, so a volcano shelled its own crater and nothing else. Drawing a *climb* between nearly
         * flat and nearly vertical, and a speed across the whole band this vent can manage, puts some
         * shots back in the caldera and sends others miles out over the flanks — which is both what a
         * volcano looks like and what makes standing three or four chunks away no kind of safety.
         *
         * The band's ceiling is what [force] buys, so a vent grows its reach as it grows its mass; its
         * floor is a share of that ceiling, so even the weakest vent varies its shots instead of firing
         * the same one every time.
         */
        fun thrownFrom(level: ServerLevel, from: BlockPos, force: Double) {
            val bomb = VolcanicBomb(AgeContent.VOLCANIC_BOMB, level)
            bomb.force = force
            bomb.setPos(from.x + HALF, from.y + HALF, from.z + HALF)
            val random = level.random

            val bearing = random.nextDouble() * FULL_TURN
            val climb = FLATTEST_SHOT + random.nextDouble() * (STEEPEST_SHOT - FLATTEST_SHOT)
            val hardest = SOFTEST_THROW + (HARDEST_THROW - SOFTEST_THROW) * force
            val speed = hardest * (WEAKEST_SHARE + random.nextDouble() * (WHOLE_SHARE - WEAKEST_SHARE))

            val across = speed * cos(climb)
            bomb.setDeltaMovement(across * cos(bearing), speed * sin(climb), across * sin(bearing))
            level.addFreshEntity(bomb)
            level.playSound(null, from, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS)
        }

        private const val HALF = 0.5
        private const val FULL_TURN = 2.0 * PI

        /** Nearly flat to nearly straight up, in radians — the dial that decides how far a shot carries. */
        private const val FLATTEST_SHOT = 0.49
        private const val STEEPEST_SHOT = 1.47

        /**
         * What a seeping vent and a full one can put behind a shot.
         *
         * At the top of the band a bomb clears seven or eight chunks before it lands; at the bottom it
         * falls back inside the crater that threw it.
         */
        private const val SOFTEST_THROW = 1.35
        private const val HARDEST_THROW = 2.6

        /** The slowest share of its own ceiling a vent will throw at, so no vent is monotonous. */
        private const val WEAKEST_SHARE = 0.55
        private const val WHOLE_SHARE = 1.0
    }
}

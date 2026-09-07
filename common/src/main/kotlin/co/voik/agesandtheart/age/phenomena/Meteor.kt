package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.location
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.Registries
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.projectile.ThrowableProjectile
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult

/**
 * One body coming in out of a meteor storm (design §5.2).
 *
 * **Not a volcanic bomb with a different texture**, though they share a renderer. A bomb arcs slowly on
 * purpose so you can read where it will land and move; a meteor comes in fast and nearly straight, and its
 * counterplay is not dodging the one you can see but being somewhere the storm is not. So they differ in
 * exactly the thing an entity is: how it flies.
 *
 * **What happens when it lands is the whole material design** (§7.1.2). Three blocks of cushion catch it
 * whole; slime hands all of its energy back; anything else and it shatters into a crater. The depth is the
 * same for every cushion on the reasoning that these arrive from space, and it is what makes the rule
 * teach itself — a body that comes down at sea is caught, and one that comes down on the shore digs a
 * crater the water fills deeper than three, so the next one there is caught too.
 */
class Meteor(type: EntityType<out Meteor>, level: Level) : ThrowableProjectile(type, level) {

    /** Nothing about one is watched by the client but its position, which vanilla already sends. */
    override fun defineSynchedData(builder: SynchedEntityData.Builder) = Unit

    /**
     * Barely any, and it hardly matters: a body arrives at a speed that makes its own path nearly straight,
     * so gravity here is a lean rather than a fall. It is [MeteorStorm] that decides how hard one comes in.
     */
    override fun getDefaultGravity(): Double = ALMOST_NONE

    override fun onHitEntity(hit: EntityHitResult) {
        super.onHitEntity(hit)
        hit.entity.hurt(damageSources().thrown(this, getOwner()), STRUCK)
    }

    override fun onHit(hit: HitResult) {
        super.onHit(hit)
        val level = level()
        if (level !is ServerLevel) return
        val at = if (hit is BlockHitResult) hit.blockPos else blockPosition()

        if (hit is BlockHitResult && level.getBlockState(at).`is`(REBOUNDS)) {
            reboundOff(level, hit)
            return
        }
        if (cushionAround(level, at) >= CAUGHT_BY) {
            settle(level, at)
        } else {
            shatter(level)
        }
        discard()
    }

    /**
     * Sent back the way it came with everything it arrived with.
     *
     * **A hundred per cent of the energy, which is not physical and is the point** (Jonah): slime is the
     * one block in the game built to give energy back, and something arriving at this speed should do
     * something absurd when it meets one. Not discarded, so it goes on being a meteor until it finds
     * somewhere that is not slime.
     */
    private fun reboundOff(level: ServerLevel, hit: BlockHitResult) {
        val face = hit.direction
        val arriving = deltaMovement
        // Reflected about the face it struck, which for an axis-aligned face is one component negated.
        deltaMovement = arriving.with(face.axis, -arriving.get(face.axis))
        level.playSound(null, hit.blockPos, SoundEvents.SLIME_BLOCK_HIT, SoundSource.BLOCKS)
    }

    /** Caught whole. There is nothing to leave behind yet — the material it carries is unnamed. */
    private fun settle(level: ServerLevel, at: BlockPos) {
        level.playSound(null, at, SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS)
    }

    private fun shatter(level: ServerLevel) {
        // Never `MOB`: that respects `mobGriefing`, and a server with the rule off would get a meteor storm
        // that leaves no craters at all. An Age was written with this in it, so it is not a setting.
        level.explode(this, x, y, z, blast, true, Level.ExplosionInteraction.TNT)
    }

    /**
     * How deep the cushion is where this landed, counted through the column it came down.
     *
     * **Both directions, because a fluid and a pad are caught differently by the same rule.** A projectile
     * has no collision with water, so a body that came down at sea flies *through* it and stops on the
     * seabed — the cushion is everything above where it stopped. A wool or honey pad is solid, so the body
     * stops at its surface and the cushion is that block and what is under it. Counting up from above the
     * impact and down from the impact itself covers the two without asking which happened.
     */
    private fun cushionAround(level: ServerLevel, at: BlockPos): Int {
        var depth = 0
        var below = at
        while (depth < CAUGHT_BY && level.getBlockState(below).`is`(CATCHES)) {
            depth++
            below = below.below()
        }
        var above = at.above()
        while (depth < CAUGHT_BY && level.getBlockState(above).`is`(CATCHES)) {
            depth++
            above = above.above()
        }
        return depth
    }

    override fun tick() {
        super.tick()
        if (!level().isClientSide) return
        shedSparks()
    }

    /**
     * The sparks it sheds coming in — an accent on the drawn streak, not the streak itself.
     *
     * **Laid along where it actually went, not at where it is.** One particle a tick at these speeds is a
     * dot every ten blocks or so, which reads as a dotted line rather than as anything burning.
     *
     * **Forced past the limiter**, because the ordinary call drops anything more than thirty-two blocks
     * from the camera — which is nearly the whole of a flight that starts a hundred and fifty out.
     */
    private fun shedSparks() {
        val travelled = position().subtract(xOld, yOld, zOld)
        val steps = travelled.length().toInt().coerceAtLeast(AT_LEAST_ONE)
        for (step in 0..<steps) {
            val along = step.toDouble() / steps
            val at = position().subtract(travelled.scale(along))
            level().addParticle(EMBER, FORCED, SHOW_ANYWAY, at.x, at.y, at.z, NO_DRIFT, NO_DRIFT, NO_DRIFT)
            if (step % EVERY_FEW != NONE_LEFT) continue
            level().addParticle(ParticleTypes.END_ROD, FORCED, SHOW_ANYWAY, at.x, at.y, at.z, NO_DRIFT, NO_DRIFT, NO_DRIFT)
        }
    }

    /** How hard this one goes off, set by the storm that threw it. */
    var blast: Float = TWICE_TNT

    override fun addAdditionalSaveData(output: net.minecraft.world.level.storage.ValueOutput) {
        super.addAdditionalSaveData(output)
        output.putFloat(BLAST_KEY, blast)
    }

    override fun readAdditionalSaveData(input: net.minecraft.world.level.storage.ValueInput) {
        super.readAdditionalSaveData(input)
        blast = input.getFloatOr(BLAST_KEY, TWICE_TNT)
    }

    companion object {
        /** Three blocks of it, whatever it is made of — these are arriving from space. */
        const val CAUGHT_BY = 3

        /** What will take the fall out of one: water, wool, honey. */
        val CATCHES: TagKey<Block> = TagKey.create(Registries.BLOCK, "catches_a_meteor".location())

        /** And what will not so much catch it as return it. */
        val REBOUNDS: TagKey<Block> = TagKey.create(Registries.BLOCK, "rebounds_a_meteor".location())

        private const val ALMOST_NONE = 0.01
        private const val STRUCK = 6.0f
        /** Twice TNT — see [MeteorStorm]. Only ever used by a body somebody summoned without a storm. */
        private const val TWICE_TNT = 8.0f

        /**
         * A violet mote, which is the one particle in the game that takes a colour it is told.
         *
         * Redstone dust as a shape and nothing of redstone as a colour — the alternative was a bespoke
         * particle type, and that needs a texture the asset pass has not written.
         */
        private val EMBER = DustParticleOptions(0x9E72FF, 0.9f)

        private const val FORCED = true
        private const val SHOW_ANYWAY = true
        private const val EVERY_FEW = 4
        private const val AT_LEAST_ONE = 1
        private const val NONE_LEFT = 0
        private const val NO_DRIFT = 0.0
        private const val BLAST_KEY = "blast"

        val ID: Identifier = "meteor".location()
    }
}

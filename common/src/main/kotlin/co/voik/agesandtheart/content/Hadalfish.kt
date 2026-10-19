package co.voik.agesandtheart.content

import net.minecraft.world.damagesource.DamageSource
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.core.registries.BuiltInRegistries
import co.voik.agesandtheart.generation.AgeChunkGenerator
import co.voik.agesandtheart.age.aspect.Rung
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.FluidTags
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal
import net.minecraft.world.entity.monster.Guardian
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level

/**
 * What hunts in the abyss — design §7.1.2's "dive under crushing pressure past what hunts there".
 *
 * **A guardian in the class hierarchy and nothing like one in play.** What extending [Guardian] is for is
 * the half nobody should write twice: `WaterBoundPathNavigation`, the water travel, the tail and spike
 * animation, and a move control that already knows how a thing with no legs holds itself in open water.
 * What it emphatically is *not* for is the laser — [registerGoals] never installs `GuardianAttackGoal` and
 * nothing here ever sets the beam's target, so the synched flag the renderer draws from stays false and
 * there is no beam. A mini-boss that opened by plinking at you from across the sea would be the wrong
 * animal entirely.
 *
 * **The elder guardian was the obvious base and is deliberately not it** (Jonah, 2026-09-10): this wears the
 * ordinary guardian's texture, scaled up, because that is the better-looking of the two. Size comes from
 * [Attributes.SCALE], which moves the hitbox and the drawing together — a renderer-only scale would have
 * left a two-block fish with a guardian's reach.
 *
 * **Eventually an anglerfish**, modelled properly, at which point the borrowed guardian art goes. The one
 * piece of that already here is the eye: `HadalfishRenderer` lights it, because a hunter you cannot see
 * coming in water you cannot see through is not a fight, it is a shrug.
 */
class Hadalfish(type: EntityType<out Hadalfish>, level: Level) : Guardian(type, level) {

    /**
     * **No `super.registerGoals()`**, which is the whole point of the class: vanilla's guardian would bring
     * its beam, its stroll and its restriction goal, and all three fight the routine in [HadalfishHunt].
     */
    override fun registerGoals() {
        goalSelector.addGoal(HUNTING, HadalfishHunt(this))
        goalSelector.addGoal(LOITERING, HadalfishLoiter(this))
        goalSelector.addGoal(IDLING, LookAtPlayerGoal(this, Player::class.java, WATCHES_FROM))
        goalSelector.addGoal(IDLING, RandomLookAroundGoal(this))
        // **Retaliates against anything at all**, including its own kind, and deliberately does not call
        // `setAlertOthers`: a shoal that ganged up on whatever hit one of them would be a shoal, and this
        // is not one. Each answers for itself.
        targetSelector.addGoal(RETALIATION, HurtByTargetGoal(this))
        // **A player is preferred, not required** (Jonah, 2026-09-10). Two goals rather than one predicate:
        // this one only ever finds people, so while anybody is in range they are the quarry, and the
        // general one below still brings it after a drowned or a squid when nobody is. Ranking rather than
        // exclusivity — a fish that ignored everything but players would be a scripted encounter, and one
        // that took the nearest body would spend the fight eating cod.
        targetSelector.addGoal(
            PREFERRED_QUARRY,
            NearestAttackableTargetGoal(this, Player::class.java, true, ::worthBiting),
        )
        targetSelector.addGoal(
            HUNTING,
            NearestAttackableTargetGoal(this, LivingEntity::class.java, true, ::worthBiting),
        )
    }

    /**
     * What it will hunt, which is **everything**, its own kind included (Jonah, 2026-09-10).
     *
     * **Nothing had to be undone to make it attack other hadalfish.** Two of them share no team, so
     * `isAlliedTo` is already false, and `TargetingConditions` only ever excludes a targeter from itself —
     * so naming `LivingEntity` as the type is the whole of it. What vanilla's monsters have instead of a
     * truce is simply no goal telling them to bother.
     *
     * **The one condition is deep water** (Jonah, 2026-10-19): it takes up nothing outside the abyss, so it
     * leaves the deep only after quarry that fled it, and then no further than a hunt goes. The retaliation
     * goal above ignores this, so shooting one still brings it as far as it can come.
     *
     * A baby of its own kind is meant to be the exception. There is no baby yet, so there is nothing here
     * to exempt — the exemption belongs in this predicate when there is.
     */
    private fun worthBiting(candidate: LivingEntity, level: ServerLevel): Boolean =
        level.getFluidState(candidate.blockPosition()).`is`(DeepWater.DEEP_WATER)

    /** Whether it is hunting something, which its lure shows — see `HadalfishRenderer`. Set by [HadalfishHunt]. */
    var isHunting: Boolean
        get() = entityData.get(HUNTING_SOMETHING)
        set(value) = entityData.set(HUNTING_SOMETHING, value)

    /** True only while it is being hit, which is what turns off the guardian's spikes — see [hurtServer]. */
    private var isTakingAHit = false

    /**
     * **No guardian's spikes** (Jonah, 2026-10-19). A guardian hurts whatever strikes it with its own thorns while it
     * is not moving, and moving is its pathing's flag, which the hunt stops to drive the fish itself — so every hit
     * on one stung back. It counts as moving for the length of a hit, and nowhere else, so its look is the guardian's.
     */
    override fun hurtServer(level: ServerLevel, source: DamageSource, damage: Float): Boolean {
        isTakingAHit = true
        try {
            return super.hurtServer(level, source, damage)
        } finally {
            isTakingAHit = false
        }
    }

    override fun isMoving(): Boolean = isTakingAHit || super.isMoving()

    override fun defineSynchedData(entityData: SynchedEntityData.Builder) {
        super.defineSynchedData(entityData)
        entityData.define(HUNTING_SOMETHING, false)
    }

    /**
     * A little more reach than vanilla's, and all round rather than only sideways, so a bite lands from
     * alongside the quarry rather than from inside it (Jonah, walked A6 2026-09-14). A fish meets its prey
     * from above and below as often as level with it.
     */
    override fun isWithinMeleeAttackRange(target: LivingEntity): Boolean =
        boundingBox.inflate(BITES_FROM).intersects(target.boundingBox)

    /**
     * How fast it is against its own speed — what a written pace or a rooting does to the attribute, carried to
     * the hunt, which drives its velocity itself and would otherwise never hear of either.
     */
    val paceShare: Double get() = (getAttributeValue(Attributes.MOVEMENT_SPEED) / CRUISE).coerceAtLeast(0.0)

    companion object {
        /**
         * **Where one may be spawned at all: in water, within reach of the deep.**
         *
         * The same line [HadalfishHunt.isAboveTheHunt] draws for giving up a chase, asked of the spawn
         * instead, so the depth it hunts in and the depth it appears in are one rule rather than two that
         * can drift.
         *
         * **Without this vanilla places it on dry ground**, because a placement is registered per
         * `EntityType` and not per class: extending [Guardian] inherits its behaviour and none of its
         * registration, so `agesandtheart:hadalfish` fell to the default of `ON_GROUND` on the surface
         * heightmap. That is how one came to hunt a frozen hillside (Jonah, 2026-09-17, the Age Tumar) —
         * and it is what `Spawns.resolved` means by vanilla re-checking every placement, which it can only
         * do for a creature that told it what to check.
         *
         * **And one natural attempt in [NATURAL_ODDS]**, which is how vanilla's own drowned and guardian rules thin
         * theirs: the abyss offers nothing else, so every monster attempt landing in it would otherwise be one.
         * Times the rung the book asks for it at, since its weight, alone in the abyss's list, can move nothing.
         */
        fun spawnsHere(
            type: EntityType<Hadalfish>,
            level: ServerLevelAccessor,
            reason: EntitySpawnReason,
            at: BlockPos,
            random: RandomSource,
        ): Boolean {
            val isInWater = level.getFluidState(at).`is`(FluidTags.WATER)
            val passesTheOdds = reason != EntitySpawnReason.NATURAL ||
                random.nextDouble() * NATURAL_ODDS < askedFor(type, level, at)
            return isInWater && !HadalfishHunt.isAboveTheHunt(level, at.y) && passesTheOdds
        }

        /** The rung this Age's book asks for hadalfish at here — ordinary outside an Age. */
        private fun askedFor(type: EntityType<Hadalfish>, level: ServerLevelAccessor, at: BlockPos): Double {
            val generator = level.level.chunkSource.generator as? AgeChunkGenerator ?: return Rung.ORDINARY
            return generator.rungOf(BuiltInRegistries.ENTITY_TYPE.getKey(type), level, at)
        }

        /**
         * Worked out rather than measured, and unwalked: about 150 attempts a tick reach this rule across an abyss
         * around a player, so a fish arrives every minute or so, and one in nine lands within the [SEES_YOU_FROM]
         * it hunts from — a stray that finds you about once in ten minutes. The rest despawn unseen, as vanilla's do.
         */
        private const val NATURAL_ODDS = 200_000

        /**
         * A mini-boss's numbers, and the shape of them is the design rather than the values.
         *
         * **Heavy, hard-hitting and not especially quick on its own account** — the speed that matters is
         * the dash, which [HadalfishHunt] drives directly rather than through this attribute. Knockback
         * resistance is total: something that could be shoved out of its own charge would be trivial to
         * handle with a shield, and the counterplay is meant to be reading the circle, not interrupting it.
         *
         * **`FOLLOW_RANGE` is shorter than its own lure carries, and that is the encounter — CORRECTED
         * 2026-09-10.** This said the range was long "because the abyss is dark: it should already be
         * coming before you can see that it is", which was true while the eye faded with the fog. It no
         * longer does: `DeepLights` carries the lure to a hundred and twenty blocks, so you see it at two
         * and a half times the range it sees you. Kept deliberately (Jonah) — a light that might be a
         * predator, and the choice of whether to go and find out, is worth more than being ambushed.
         */
        fun createAttributes(): AttributeSupplier.Builder = Monster.createMonsterAttributes()
            .add(Attributes.MAX_HEALTH, HEALTH)
            .add(Attributes.ATTACK_DAMAGE, BITE)
            .add(Attributes.MOVEMENT_SPEED, CRUISE)
            .add(Attributes.FOLLOW_RANGE, SEES_YOU_FROM)
            .add(Attributes.KNOCKBACK_RESISTANCE, IMMOVABLE)
            .add(Attributes.SCALE, TIMES_A_GUARDIAN)

        private const val HEALTH = 120.0

        /** Per strike, and it lands three of them — see [HadalfishHunt]. */
        private const val BITE = 9.0

        /** How far past its own body a bite reaches, every way. Vanilla's is about 0.83, and sideways only. */
        const val BITES_FROM = 1.5

        /** Only what it loiters at. The charge is not this number. */
        private const val CRUISE = 0.4

        private const val SEES_YOU_FROM = 48.0

        private const val IMMOVABLE = 1.0

        /** A guardian is 0.85 across, so this is a shade over two blocks — big, without being a monument. */
        private const val TIMES_A_GUARDIAN = 2.5

        /**
         * Retaliation first, then people, then anything at all.
         *
         * Three ranks where there were two, because preferring a player is a *rank* and not a rule — see
         * [registerGoals]. Whatever just hit it still outranks both: an animal that ignored the thing
         * wounding it to swim at somebody else reads as scripted rather than as hungry.
         */
        private const val RETALIATION = 1
        private const val PREFERRED_QUARRY = 2
        private const val HUNTING = 3
        private const val LOITERING = 5
        private const val IDLING = 8

        private const val WATCHES_FROM = 16.0f

        private val HUNTING_SOMETHING: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(Hadalfish::class.java, EntityDataSerializers.BOOLEAN)
    }
}

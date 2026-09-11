package co.voik.agesandtheart.content

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
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
     * **The one condition is water, and it is about reach rather than mercy.** Something absurdly hostile
     * that fixated on a cow across a beach would spend the rest of its life failing to arrive, which reads
     * as broken rather than as vicious — and the retaliation goal above ignores this, so shooting one from
     * dry land still brings it as far as it can come.
     *
     * A baby of its own kind is meant to be the exception. There is no baby yet, so there is nothing here
     * to exempt — the exemption belongs in this predicate when there is.
     */
    private fun worthBiting(candidate: LivingEntity, level: ServerLevel): Boolean = candidate.isInWater

    companion object {
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

        /** Only what it loiters at. The charge is not this number. */
        private const val CRUISE = 0.4

        private const val SEES_YOU_FROM = 48.0

        private const val IMMOVABLE = 1.0

        /** A guardian is 0.85 across, so this is a shade over two blocks — big, without being a monument. */
        private const val TIMES_A_GUARDIAN = 2.5

        private const val HUNTING = 1
        private const val RETALIATION = 1
        private const val LOITERING = 5
        private const val IDLING = 8

        private const val WATCHES_FROM = 16.0f
    }
}

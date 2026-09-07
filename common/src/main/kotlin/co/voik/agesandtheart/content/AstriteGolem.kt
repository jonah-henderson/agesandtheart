package co.voik.agesandtheart.content

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.AgeableMob
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.TamableAnimal
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.FollowOwnerGoal
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal
import net.minecraft.world.entity.ai.goal.RandomStrollGoal
import net.minecraft.world.entity.ai.goal.SitWhenOrderedToGoal
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal
import net.minecraft.world.entity.ai.goal.target.OwnerHurtByTargetGoal
import net.minecraft.world.entity.ai.goal.target.OwnerHurtTargetGoal
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

/**
 * A golem assembled out of astrite, which follows the one who assembled it (design §7.1.2).
 *
 * **A tamable rather than a golem, in the class hierarchy** — and that is the opposite of what it looks
 * like it should be. Nearly everything asked of this one lives on [TamableAnimal]: following, sitting when
 * told, teleporting when it cannot keep up, and fighting whatever attacks its owner. `AbstractGolem` is a
 * `PathfinderMob` on a different branch and carries what we do *not* want — village defence, offering
 * poppies, cracking — so extending the iron golem would have meant inheriting the wrong half and writing
 * the right half by hand. It borrows the iron golem's *appearance* and none of its behaviour.
 *
 * **It cannot outrun you and is not meant to.** Its speed is a sprint and no more, so anything faster —
 * skates, an elytra, a horse — leaves it behind, and the teleport that [FollowOwnerGoal] already carries is
 * what closes the gap. A companion that could match any speed would make the material's own skates
 * pointless.
 *
 * The breeding is inherited and unwanted: [getBreedOffspring] answers nothing and no breeding goal is
 * registered, which is the price of the branch that had everything else.
 */
class AstriteGolem(type: EntityType<out AstriteGolem>, level: Level) : TamableAnimal(type, level) {

    override fun registerGoals() {
        goalSelector.addGoal(SWIMMING, FloatGoal(this))
        goalSelector.addGoal(BEING_TOLD, SitWhenOrderedToGoal(this))
        goalSelector.addGoal(FIGHTING, MeleeAttackGoal(this, ATTACK_PACE, true))
        goalSelector.addGoal(KEEPING_UP, FollowOwnerGoal(this, KEEPING_PACE, GETS_THIS_FAR, CLOSE_ENOUGH))
        goalSelector.addGoal(IDLING, RandomStrollGoal(this, WANDERING_PACE))
        goalSelector.addGoal(LOOKING, LookAtPlayerGoal(this, Player::class.java, NOTICES_AT))
        goalSelector.addGoal(LOOKING, RandomLookAroundGoal(this))

        // What it fights, in the order it decides: what hurt its owner, what its owner hit, what hurt it,
        // and then anything hostile on its own account.
        targetSelector.addGoal(FOR_ITS_OWNER, OwnerHurtByTargetGoal(this))
        targetSelector.addGoal(FOR_ITS_OWNER, OwnerHurtTargetGoal(this))
        targetSelector.addGoal(FOR_ITSELF, HurtByTargetGoal(this))
        targetSelector.addGoal(ON_PATROL, NearestAttackableTargetGoal(this, Monster::class.java, true))
    }

    /**
     * Right-clicking it puts it to sleep and wakes it again, which is a dog's contract and reads the same.
     *
     * Only for whoever assembled it — a golem that anybody could switch off is not a companion.
     */
    override fun mobInteract(player: Player, hand: net.minecraft.world.InteractionHand): InteractionResult {
        if (!isOwnedBy(player)) return super.mobInteract(player, hand)
        if (level().isClientSide) return InteractionResult.SUCCESS
        isOrderedToSit = !isOrderedToSit
        jumping = false
        navigation.stop()
        target = null
        return InteractionResult.SUCCESS
    }

    /** Assembled, never bred, and it does not eat. */
    override fun getBreedOffspring(level: ServerLevel, partner: AgeableMob): AgeableMob? = null

    override fun isFood(stack: ItemStack): Boolean = false

    /** Whoever stood it up owns it, which is the one thing assembling it has to record. */
    fun answerTo(owner: Player) {
        tame(owner)
    }

    companion object {

        /**
         * **Tougher and harder-hitting than an iron golem** (Jonah), which is what thirty-six shards buys.
         *
         * The speed is the interesting one: it is a sprint, deliberately, so the thing keeps up with a
         * player on foot and with nothing else.
         */
        fun createAttributes(): AttributeSupplier.Builder = createMobAttributes()
            .add(Attributes.MAX_HEALTH, TOUGHER_THAN_IRON)
            .add(Attributes.ATTACK_DAMAGE, HARDER_THAN_IRON)
            .add(Attributes.MOVEMENT_SPEED, A_SPRINT)
            .add(Attributes.KNOCKBACK_RESISTANCE, IMMOVABLE)
            .add(Attributes.ATTACK_KNOCKBACK, SENDS_THEM_FLYING)
            .add(Attributes.STEP_HEIGHT, CLIMBS_A_BLOCK)

        /** An iron golem is 100 and 15. */
        private const val TOUGHER_THAN_IRON = 120.0
        private const val HARDER_THAN_IRON = 18.0

        /**
         * Fast enough to stay with somebody running and no faster — a wolf's number, which is about what a
         * sprint comes to once a mob's own movement is worked out.
         */
        private const val A_SPRINT = 0.32

        private const val IMMOVABLE = 1.0
        private const val SENDS_THEM_FLYING = 1.0
        private const val CLIMBS_A_BLOCK = 1.0

        /** How keenly it does each thing, against its own walking speed. */
        private const val ATTACK_PACE = 1.0
        private const val KEEPING_PACE = 1.0
        private const val WANDERING_PACE = 0.8

        /** It sets off after you past this and stops this close, in blocks. */
        private const val GETS_THIS_FAR = 10.0f
        private const val CLOSE_ENOUGH = 2.0f
        private const val NOTICES_AT = 8.0f

        /** Goal priorities, lowest first, which is how vanilla orders them. */
        private const val SWIMMING = 0
        private const val BEING_TOLD = 1
        private const val FIGHTING = 2
        private const val KEEPING_UP = 3
        private const val IDLING = 4
        private const val LOOKING = 5

        private const val FOR_ITS_OWNER = 1
        private const val FOR_ITSELF = 2
        private const val ON_PATROL = 3
    }
}

package co.voik.agesandtheart.content

import co.voik.agesandtheart.location
import co.voik.agesandtheart.optics.ArmedBeams
import co.voik.ephemeris.sky.LevelLooks
import co.voik.ephemeris.sky.SkyReading
import co.voik.ephemeris.sky.SkySpec
import java.util.EnumSet
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.registries.Registries
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
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

    /** Shots left in it, out of [FULL_CHARGE]. Synced so it can be drawn dimming as it empties. */
    var charge: Int
        get() = entityData.get(CHARGE)
        private set(value) = entityData.set(CHARGE, value.coerceIn(0, FULL_CHARGE))

    /** The creature it last fired at, while the shot is still shown, or null. */
    val shownLaserTarget: Entity? get() = entityData.get(LASER_TARGET).takeIf { it != NO_TARGET }?.let(level()::getEntity)

    /** How far toward the next shot the light around it has brought it, never saved. */
    private var refilled = 0f
    private var refillRate = 0f
    private var laserReadyIn = 0
    private var laserShownFor = 0

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(CHARGE, FULL_CHARGE)
        builder.define(LASER_TARGET, NO_TARGET)
    }

    override fun registerGoals() {
        goalSelector.addGoal(SWIMMING, FloatGoal(this))
        goalSelector.addGoal(BEING_TOLD, SitWhenOrderedToGoal(this))
        goalSelector.addGoal(FIRING, FiringGoal())
        // **Swinging faster than vanilla's twenty ticks**, which is the one part of "harder-hitting" that
        // is not an attribute: `MeleeAttackGoal` hard-codes its interval, so it takes a subclass to say.
        goalSelector.addGoal(
            FIGHTING,
            object : MeleeAttackGoal(this, ATTACK_PACE, true) {
                override fun getAttackInterval(): Int = adjustedTickDelay(SWINGS_EVERY)
            },
        )
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
    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        val holding = player.getItemInHand(hand)
        if (holding.`is`(AgeContent.ASTRITE_SHARD) && health < maxHealth) return mendWith(player, holding)
        if (!isOwnedBy(player)) return super.mobInteract(player, hand)
        if (level().isClientSide) return InteractionResult.SUCCESS
        val isAWeaponOrTool = holding.has(DataComponents.WEAPON) || holding.has(DataComponents.TOOL)
        val isAskingForItBack = player.isSecondaryUseActive && holding.isEmpty && !mainHandItem.isEmpty
        if (isAWeaponOrTool || isAskingForItBack) return swapHeldWith(player, hand)
        isOrderedToSit = !isOrderedToSit
        jumping = false
        navigation.stop()
        target = null
        return InteractionResult.SUCCESS
    }

    /**
     * A shard of what it is made of puts some of it back — an iron golem's contract, in this material.
     *
     * **Anybody may mend one, and only its owner may switch it off.** Repairing somebody's golem is a
     * kindness and there is no reason to forbid it; ordering one about is not.
     */
    private fun mendWith(player: Player, shard: net.minecraft.world.item.ItemStack): InteractionResult {
        val level = level()
        if (level !is ServerLevel) return InteractionResult.SUCCESS
        heal(MENDED_BY)
        shard.consume(ONE_SHARD, player)
        playSound(SoundEvents.IRON_GOLEM_REPAIR, REPAIR_VOLUME, REPAIR_PITCH)
        // **Seen as well as heard.** The sound alone left mending a thing you did rather than a thing that
        // happened (Jonah, 2026-09-09): a golem at full health and one just mended looked identical, and
        // the shard going out of the hand was the only sign it had worked. Astrite's own violet, so what
        // is going in is legible as the material rather than as a generic heal.
        level.sendParticles(
            MENDING,
            x,
            y + bbHeight * MENDED_UP_THE_BODY,
            z,
            MENDING_MOTES,
            bbWidth * MENDING_SPREAD,
            bbHeight * MENDING_SPREAD,
            bbWidth * MENDING_SPREAD,
            MENDING_DRIFT,
        )
        return InteractionResult.SUCCESS
    }

    /** What it holds and what [player] holds change hands: given a weapon or tool, or giving one back. */
    private fun swapHeldWith(player: Player, hand: InteractionHand): InteractionResult {
        val given = player.getItemInHand(hand)
        player.setItemInHand(hand, mainHandItem)
        setItemSlot(EquipmentSlot.MAINHAND, given)
        setGuaranteedDrop(EquipmentSlot.MAINHAND)
        playSound(SoundEvents.ITEM_FRAME_ADD_ITEM)
        return InteractionResult.SUCCESS
    }

    /** A blow with what it holds wears it as it would wear in a player's hand, which a mob's blow does not. */
    override fun doHurtTarget(level: ServerLevel, target: Entity): Boolean {
        val landed = super.doHurtTarget(level, target)
        if (landed && target is LivingEntity && !mainHandItem.isEmpty) mainHandItem.postHurtEnemy(target, this)
        return landed
    }

    override fun tick() {
        super.tick()
        val level = level() as? ServerLevel ?: return
        if (tickCount % LIGHT_READ_EVERY == 0) refillRate = refillRateIn(level)
        refillFromLight()
        if (laserReadyIn > 0) laserReadyIn--
        if (laserShownFor > 0 && --laserShownFor == 0) entityData.set(LASER_TARGET, NO_TARGET)
        slowWhenDrained()
    }

    private fun refillFromLight() {
        if (charge >= FULL_CHARGE) {
            refilled = 0f
            return
        }
        refilled += refillRate
        if (refilled < 1f) return
        refilled -= 1f
        charge++
    }

    /**
     * Shots a tick the light where it stands gives back: sunlight quickly, by how much sun is up, then block light
     * slowly, and nothing in the dark.
     */
    private fun refillRateIn(level: ServerLevel): Float {
        val eyes = BlockPos.containing(eyePosition)
        val sunlight = if (level.getEffectiveSkyBrightness(eyes) >= SUNLIT) sunlightIn(level) else 0f
        if (sunlight > 0f) return sunlight / TICKS_A_SHOT_IN_SUNLIGHT
        return if (level.getBrightness(LightLayer.BLOCK, eyes) >= LAMPLIT) 1f / TICKS_A_SHOT_IN_LAMPLIGHT else 0f
    }

    /** How much sun is up against vanilla's one: each sun up, by its size and its brightness. */
    private fun sunlightIn(level: ServerLevel): Float {
        val sky = LevelLooks.anywhere(level)?.sky ?: SkySpec.VANILLA
        val sunsUp = SkyReading.of(sky, level.defaultClockTime).suns.filter { it.isUp }
        val sunlight = sunsUp.sumOf { sun ->
            val appearance = sun.body.appearance
            (appearance.angularSize / SkySpec.VANILLA_SUN_SIZE * appearance.tint.brightness).toDouble()
        }
        return sunlight.toFloat().coerceAtMost(MOST_SUNLIGHT)
    }

    private fun slowWhenDrained() {
        val speed = getAttribute(Attributes.MOVEMENT_SPEED) ?: return
        val isSlowed = speed.hasModifier(DRAINED_ID)
        when {
            charge <= 0 && !isSlowed -> speed.addTransientModifier(DRAINED)
            charge > 0 && isSlowed -> speed.removeModifier(DRAINED_ID)
        }
    }

    private fun canFireAt(aim: LivingEntity?): Boolean {
        if (aim == null || !aim.isAlive || charge <= 0) return false
        val distance = distanceTo(aim)
        val isInRange = distance > MELEE_REACH && distance <= LASER_REACH
        return isInRange && hasLineOfSight(aim)
    }

    private fun fireAt(level: ServerLevel, aim: LivingEntity) {
        val laser = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ArmedBeams.LASER)
        aim.hurtServer(level, DamageSource(laser, this), LASER_DAMAGE)
        charge--
        laserReadyIn = FIRES_EVERY
        laserShownFor = LASER_SHOWN_FOR
        entityData.set(LASER_TARGET, aim.id)
        playSound(SoundEvents.AMETHYST_BLOCK_RESONATE, LASER_VOLUME, LASER_PITCH)
    }

    /** Standing to fire at what it fights from past arm's reach, while it has charge and sees it. */
    private inner class FiringGoal : Goal() {
        init {
            flags = EnumSet.of(Flag.MOVE, Flag.LOOK)
        }

        override fun canUse(): Boolean = canFireAt(target)

        override fun requiresUpdateEveryTick(): Boolean = true

        override fun start() {
            navigation.stop()
        }

        override fun tick() {
            val aim = target ?: return
            lookControl.setLookAt(aim, TURNS_TO_AIM, TURNS_TO_AIM)
            val level = level() as? ServerLevel ?: return
            if (laserReadyIn <= 0) fireAt(level, aim)
        }
    }

    override fun addAdditionalSaveData(output: ValueOutput) {
        super.addAdditionalSaveData(output)
        output.putInt(CHARGE_KEY, charge)
    }

    override fun readAdditionalSaveData(input: ValueInput) {
        super.readAdditionalSaveData(input)
        charge = input.getIntOr(CHARGE_KEY, FULL_CHARGE)
    }

    /** Assembled, never bred, and it does not eat. */
    override fun getBreedOffspring(level: ServerLevel, partner: AgeableMob): AgeableMob? = null

    override fun isFood(stack: ItemStack): Boolean = false

    companion object {
        private val CHARGE: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(AstriteGolem::class.java, EntityDataSerializers.INT)
        private val LASER_TARGET: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(AstriteGolem::class.java, EntityDataSerializers.INT)
        private const val NO_TARGET = -1
        private const val CHARGE_KEY = "laser_charge"

        const val FULL_CHARGE = 48

        /** Its laser: what a shot hurts, how often, and how far, in blocks; closer than [MELEE_REACH] it swings. */
        private const val LASER_DAMAGE = 6f
        private const val FIRES_EVERY = 20
        private const val LASER_REACH = 24.0
        private const val MELEE_REACH = 3.0
        private const val LASER_SHOWN_FOR = 6
        private const val TURNS_TO_AIM = 30f
        private const val LASER_VOLUME = 1.0f
        private const val LASER_PITCH = 1.6f

        /** Sky light it counts as sunlit in, as a collector does, and block light it refills by at all. */
        private const val SUNLIT = 12
        private const val LAMPLIT = 8
        private const val TICKS_A_SHOT_IN_SUNLIGHT = 40f
        private const val TICKS_A_SHOT_IN_LAMPLIGHT = 400f
        private const val MOST_SUNLIGHT = 4f
        private const val LIGHT_READ_EVERY = 20

        private val DRAINED_ID: Identifier = "astrite_golem_drained".location()
        private val DRAINED = AttributeModifier(DRAINED_ID, -1.0 / 3.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)

        /**
         * **Tougher than an iron golem but a lighter blow** (Jonah), its laser and whatever it is handed making up the
         * difference.
         *
         * The speed is the interesting one: it is a sprint, deliberately, so the thing keeps up with a
         * player on foot and with nothing else.
         */
        fun createAttributes(): AttributeSupplier.Builder = createMobAttributes()
            .add(Attributes.MAX_HEALTH, TOUGHER_THAN_IRON)
            .add(Attributes.ATTACK_DAMAGE, LIGHTER_THAN_IRON)
            .add(Attributes.MOVEMENT_SPEED, A_SPRINT)
            .add(Attributes.KNOCKBACK_RESISTANCE, IMMOVABLE)
            .add(Attributes.ATTACK_KNOCKBACK, SENDS_THEM_FLYING)
            .add(Attributes.STEP_HEIGHT, CLIMBS_A_BLOCK)

        /**
         * An iron golem is 100 and 15, and swings every twenty ticks; a held weapon adds its own damage to [LIGHTER_THAN_IRON].
         */
        private const val TOUGHER_THAN_IRON = 150.0
        private const val LIGHTER_THAN_IRON = 6.0
        private const val SWINGS_EVERY = 15

        /** What a shard puts back, against an iron ingot's 25 on the golem it is shaped after. */
        private const val MENDED_BY = 25.0f
        private const val ONE_SHARD = 1
        private const val REPAIR_VOLUME = 1.0f
        private const val REPAIR_PITCH = 1.0f

        /** What mending looks like: astrite's own violet, about the body. */
        private val MENDING = DustParticleOptions(AgeContent.ASTRITE_TINT, 1.0f)
        private const val MENDING_MOTES = 24
        private const val MENDING_SPREAD = 0.45
        private const val MENDED_UP_THE_BODY = 0.6
        private const val MENDING_DRIFT = 0.02

        /**
         * Fast enough to stay with somebody sprinting.
         *
         * **This is a ceiling, not a cruising speed, and that is why it is not 0.13.** `MoveControl` sets
         * a mob's speed to its attribute times the goal's modifier, in the same units a player's movement
         * speed uses — where walking is 0.1 and a sprint is 0.13. So a number equal to a sprint would make
         * this *slower* than one, because pathfinding never realises the ceiling: recalculating a route
         * after a moving target, cornering and stopping to re-aim all cost speed a player does not pay.
         * The ceiling is set well above a sprint so the realised pace can reach one, and the teleport
         * inside [FollowOwnerGoal] covers what is left (Jonah, walked: it was falling behind at 0.32).
         *
         * It still loses to skates and an elytra, which are far above even this.
         */
        private const val A_SPRINT = 0.42

        private const val IMMOVABLE = 1.0
        private const val SENDS_THEM_FLYING = 1.0
        private const val CLIMBS_A_BLOCK = 1.0

        /** How keenly it does each thing, against its own walking speed. */
        private const val ATTACK_PACE = 1.0
        private const val KEEPING_PACE = 1.0
        private const val WANDERING_PACE = 0.8

        /**
         * It sets off after you past this and stops this close, in blocks.
         *
         * Sooner than vanilla's ten: a companion that waits until you are ten blocks off spends the whole
         * time catching up, and never looks like it is *with* you.
         */
        private const val GETS_THIS_FAR = 6.0f
        private const val CLOSE_ENOUGH = 2.0f
        private const val NOTICES_AT = 8.0f

        /** Goal priorities, lowest first, which is how vanilla orders them. */
        private const val SWIMMING = 0
        private const val BEING_TOLD = 1
        private const val FIRING = 2
        private const val FIGHTING = 3
        private const val KEEPING_UP = 4
        private const val IDLING = 5
        private const val LOOKING = 6

        private const val FOR_ITS_OWNER = 1
        private const val FOR_ITSELF = 2
        private const val ON_PATROL = 3
    }
}

package co.voik.agesandtheart.content

import co.voik.agesandtheart.location
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.tags.TagKey
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.AgeableMob
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.control.FlyingMoveControl
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.FollowParentGoal
import net.minecraft.world.entity.ai.goal.TemptGoal
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation
import net.minecraft.world.entity.ai.navigation.PathNavigation
import net.minecraft.world.entity.ai.util.AirRandomPos
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.pathfinder.PathType
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.Vec3
import java.util.Optional
import kotlin.math.PI

/**
 * The scarab: the beetle D'ni ink was made from, and the reward for an Age whose habitat is struck exactly
 * (design §7.1.2).
 *
 * **One creature, and whether it is a stray is only whether it has found a home.** Scarabs arrive homeless
 * ([ScarabArrivals]), take an empty nest where there is one ([ScarabClaim]), and otherwise work on the
 * colony's pillars until one is laid ([ScarabBuild]) or go looking for a colony ([ScarabSettle]); where the
 * Age falls short there is nothing, and a scarab that never had a home wanders off in the end. Once it has a
 * nest — or was born to one — it stays: it sleeps in it at night ([ScarabRoost]), grazes torchflowers
 * ([ScarabGraze]), builds at leisure, and breeds where an empty nest stands ([ScarabBreed]). Hunger stops
 * building and breeding and nothing else.
 *
 * **It flies**, being drawn as a bee: bee movement is vanilla's already, a pillar top is somewhere a
 * flier can reach, and a stray crosses jungle canopy that a walker would spend its life pathfinding under.
 *
 * **Hand-feeding satisfies hunger and is not a breeding trigger**, so [isFood] is false: a torchflower or a
 * mushroom is a meal, never love. Breeding is the colony's own decision, and the free site is its gate.
 */
class Scarab(type: EntityType<out Scarab>, level: Level) : Animal(type, level) {

    /** The nest this scarab claimed, or null while it has none. */
    var home: BlockPos? = null
        private set

    /** Whether it has ever had a nest or was born to a colony; a scarab that has neither cannot last. */
    var belongsToAColony: Boolean = false
        private set

    /** Where it last ate, which is where it goes back to when nothing is in reach. */
    var lastMeal: BlockPos? = null

    /** How long before it may go into its nest, so one let out at dawn does not go straight back in. */
    var stayOutOfTheNestFor: Int = 0

    private var satiety: Int = MEAL
    private var mushroomFedFor: Int = 0
    private var strayFor: Int = 0

    private var headingFor: Vec3? = null
    private var repathIn: Int = 0

    init {
        moveControl = FlyingMoveControl(this, MAX_TURN_DEGREES, true)
        setPathfindingMalus(PathType.FIRE, IMPASSABLE)
        setPathfindingMalus(PathType.WATER, IMPASSABLE)
        setPathfindingMalus(PathType.WATER_BORDER, SHY_OF_WATER)
        setPathfindingMalus(PathType.COCOA, IMPASSABLE)
        setPathfindingMalus(PathType.FENCE, IMPASSABLE)
    }

    override fun defineSynchedData(entityData: SynchedEntityData.Builder) {
        super.defineSynchedData(entityData)
        entityData.define(CARRIED, Optional.empty())
    }

    override fun registerGoals() {
        goalSelector.addGoal(ROOSTING, ScarabRoost(this))
        goalSelector.addGoal(BREEDING, ScarabBreed(this))
        goalSelector.addGoal(TEMPTED, TemptGoal(this, TEMPTED_SPEED, { mealOf(it) != null }, false))
        goalSelector.addGoal(GRAZING, ScarabGraze(this))
        goalSelector.addGoal(CLAIMING, ScarabClaim(this))
        goalSelector.addGoal(WORKING, ScarabBuild(this))
        goalSelector.addGoal(FOLLOWING, FollowParentGoal(this, FOLLOWING_SPEED))
        goalSelector.addGoal(SEEKING, ScarabSettle(this))
        goalSelector.addGoal(WANDERING, ScarabWander(this))
        goalSelector.addGoal(FLOATING, FloatGoal(this))
    }


    val isHoused: Boolean get() = home != null

    val isHungry: Boolean get() = satiety <= 0

    /**
     * Whether it would eat now: only once its last meal is spent. It does not stock up, so a colony eats
     * twice a day — at dawn, waking hungry, and at dusk, when the morning's meal runs out.
     */
    val hasRoomToEat: Boolean get() = satiety <= 0

    val isMushroomFed: Boolean get() = mushroomFedFor > 0

    /** Adult, rested from breeding, housed and fed — the one scarab [ScarabBreed] will pair. */
    val canBreedNow: Boolean get() = !isBaby && age == 0 && isHoused && !isHungry && isAlive

    /** The sand it is carrying to its pillar, or null. Synched, so the renderer can draw it. */
    val carried: BlockState? get() = entityData.get(CARRIED).orElse(null)

    fun carry(sand: BlockState?) = entityData.set(CARRIED, Optional.ofNullable(sand))

    fun wantsToRoost(): Boolean =
        isHoused && stayOutOfTheNestFor <= 0 && ScarabNestBlockEntity.isTimeToRoost(level())

    /** Its own nest, where that is loaded and still holds its claim; null otherwise. */
    fun nest(): ScarabNestBlockEntity? {
        val at = home ?: return null
        if (!level().isLoaded(at)) return null
        val nest = level().getBlockEntity(at) as? ScarabNestBlockEntity ?: return null
        return if (nest.belongsTo(this)) nest else null
    }

    fun mayDisturbTheWorld(level: ServerLevel): Boolean = level.gameRules.get(GameRules.MOB_GRIEFING)


    fun settleIn(nest: BlockPos) {
        home = nest
        belongsToAColony = true
        strayFor = 0
    }

    /** A colony's young stays whatever happens to it, as a scarab that has had a home does. */
    fun bornToAColony() {
        belongsToAColony = true
    }

    /** Leaves its nest free for another, where it still has one. */
    fun giveUpHome() {
        nest()?.vacate(this)
        home = null
    }

    fun eat(meal: Meal) {
        satiety = MEAL
        if (meal == Meal.MUSHROOM) mushroomFedFor = MUSHROOM_BOOST
        playSound(SoundEvents.GENERIC_EAT.value(), EATING_VOLUME, EATING_PITCH)
    }

    /** Let out of its nest after [ticks] asleep: it has grown up by as much, as a bee does in a hive. */
    fun wokeAfter(ticks: Int) {
        stayOutOfTheNestFor = STAY_OUT_AFTER_WAKING
        // A night's sleep spends the evening's meal, so it comes out at dawn looking for its breakfast.
        satiety = 0
        if (isAgeLocked) return
        val grownBy = if (age < 0) minOf(0, age + ticks) else maxOf(0, age - ticks)
        age = grownBy
    }

    /**
     * Flies toward [target]: straight at it once close, by pathfinding within reach, and a step at a time
     * beyond that — bee movement, which a pathfinder cannot do in one go past a few dozen blocks.
     */
    fun headFor(target: Vec3, speed: Double = CRUISING) {
        val distance = position().distanceTo(target)
        if (distance < HOVERING_REACH) {
            navigation.stop()
            moveControl.setWantedPosition(target.x, target.y, target.z, speed)
            headingFor = target
            return
        }
        if (repathIn > 0) repathIn--
        val stillOnTheWay = navigation.isInProgress && target == headingFor
        if (stillOnTheWay || repathIn > 0) return
        repathIn = REPATH_EVERY
        headingFor = target
        if (distance < PATHFINDING_REACH) {
            navigation.moveTo(target.x, target.y, target.z, speed)
            return
        }
        val step = AirRandomPos.getPosTowards(this, STEP_ACROSS, STEP_UP, climbToward(target), target, STEP_SPREAD)
            ?: return
        navigation.moveTo(step.x, step.y, step.z, speed)
    }

    fun isNear(target: Vec3, within: Double): Boolean = position().distanceToSqr(target) < within * within

    private fun climbToward(target: Vec3): Int = when {
        target.y - y > CLIMB_THRESHOLD -> CLIMB
        y - target.y > CLIMB_THRESHOLD -> -CLIMB
        else -> 0
    }

    override fun customServerAiStep(level: ServerLevel) {
        super.customServerAiStep(level)
        if (satiety > 0) satiety--
        if (stayOutOfTheNestFor > 0) stayOutOfTheNestFor--
        feelTheMushrooms()
        if (tickCount % CHECK_THE_NEST_EVERY == 0) checkTheNest()
        if (!belongsToAColony && ++strayFor > STRAY_LIFETIME) wanderOff(level)
    }

    private fun feelTheMushrooms() {
        val wasFed = isMushroomFed
        if (mushroomFedFor > 0) mushroomFedFor--
        val speed = getAttribute(Attributes.FLYING_SPEED) ?: return
        when {
            isMushroomFed && !speed.hasModifier(MUSHROOM_SPEED_ID) -> speed.addTransientModifier(MUSHROOM_SPEED)
            wasFed && !isMushroomFed -> speed.removeModifier(MUSHROOM_SPEED_ID)
        }
    }

    /** A nest broken, or taken by another, is not a home. Asked only where the nest is loaded to answer. */
    private fun checkTheNest() {
        val at = home ?: return
        if (level().isLoaded(at) && nest() == null) home = null
    }

    /** A stray that never found a home goes, rather than lingering as a colony of one. */
    private fun wanderOff(level: ServerLevel) {
        val middle = y + bbHeight / 2
        level.sendParticles(ParticleTypes.POOF, x, middle, z, PUFFS, PUFF_SPREAD, PUFF_SPREAD, PUFF_SPREAD, 0.0)
        discard()
    }

    override fun die(source: DamageSource) {
        giveUpHome()
        super.die(source)
    }

    /** One carried through a portal leaves its nest behind it for the next, as one that died does. */
    override fun remove(reason: RemovalReason) {
        if (reason == RemovalReason.CHANGED_DIMENSION) giveUpHome()
        super.remove(reason)
    }


    override fun isFood(itemStack: ItemStack): Boolean = false

    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        val held = player.getItemInHand(hand)
        val meal = mealOf(held) ?: return super.mobInteract(player, hand)
        val growsFromIt = canAgeUp()
        if (!hasRoomToEat && !growsFromIt) return super.mobInteract(player, hand)
        if (level().isClientSide) return InteractionResult.CONSUME
        usePlayerItem(player, hand, held)
        eat(meal)
        if (growsFromIt) ageUp(getSpeedUpSecondsWhenFeeding(-age), true)
        return InteractionResult.SUCCESS_SERVER
    }

    override fun getBreedOffspring(level: ServerLevel, partner: AgeableMob): Scarab? =
        AgeContent.SCARAB.create(level, EntitySpawnReason.BREEDING)


    override fun createNavigation(level: Level): PathNavigation {
        val navigation = object : FlyingPathNavigation(this, level) {
            override fun isStableDestination(pos: BlockPos): Boolean = !this.level.getBlockState(pos.below()).isAir
        }
        navigation.setCanOpenDoors(false)
        navigation.setCanFloat(false)
        navigation.setRequiredPathLength(PATH_SEARCH_REACH)
        return navigation
    }

    override fun getWalkTargetValue(pos: BlockPos, level: LevelReader): Float =
        if (level.getBlockState(pos).isAir) PREFERS_AIR else 0.0f

    override fun checkFallDamage(ya: Double, onGround: Boolean, onState: BlockState, pos: BlockPos) = Unit

    override fun isFlapping(): Boolean = !onGround() && tickCount % TICKS_PER_FLAP == 0

    override fun omnidirectionalAirMover(): Boolean = true

    override fun jumpInLiquid(type: TagKey<Fluid>) {
        deltaMovement = deltaMovement.add(0.0, LIFT_OUT_OF_LIQUID, 0.0)
    }

    override fun getAmbientSound(): SoundEvent? = null

    override fun getHurtSound(source: DamageSource): SoundEvent = SoundEvents.BEE_HURT

    override fun getDeathSound(): SoundEvent = SoundEvents.BEE_DEATH

    override fun getSoundVolume(): Float = QUIET


    override fun addAdditionalSaveData(output: ValueOutput) {
        super.addAdditionalSaveData(output)
        output.storeNullable(HOME_KEY, BlockPos.CODEC, home)
        output.storeNullable(LAST_MEAL_KEY, BlockPos.CODEC, lastMeal)
        output.storeNullable(CARRIED_KEY, BlockState.CODEC, carried)
        output.putBoolean(COLONY_KEY, belongsToAColony)
        output.putInt(SATIETY_KEY, satiety)
        output.putInt(MUSHROOM_KEY, mushroomFedFor)
        output.putInt(STRAY_KEY, strayFor)
    }

    override fun readAdditionalSaveData(input: ValueInput) {
        super.readAdditionalSaveData(input)
        home = input.read(HOME_KEY, BlockPos.CODEC).orElse(null)
        lastMeal = input.read(LAST_MEAL_KEY, BlockPos.CODEC).orElse(null)
        carry(input.read(CARRIED_KEY, BlockState.CODEC).orElse(null))
        belongsToAColony = input.getBooleanOr(COLONY_KEY, false)
        satiety = input.getIntOr(SATIETY_KEY, MEAL)
        mushroomFedFor = input.getIntOr(MUSHROOM_KEY, 0)
        strayFor = input.getIntOr(STRAY_KEY, 0)
    }

    /** What a scarab eats. A mushroom feeds it the same and quickens it besides (design §7.1.2). */
    enum class Meal { TORCHFLOWER, MUSHROOM }

    companion object {
        private val CARRIED: EntityDataAccessor<Optional<BlockState>> =
            SynchedEntityData.defineId(Scarab::class.java, EntityDataSerializers.OPTIONAL_BLOCK_STATE)

        /** The meal a held item makes, or null where it is none. */
        fun mealOf(stack: ItemStack): Meal? = when {
            stack.`is`(Items.TORCHFLOWER) -> Meal.TORCHFLOWER
            MUSHROOMS.any(stack::`is`) -> Meal.MUSHROOM
            else -> null
        }

        private val MUSHROOMS = listOf(
            Items.BROWN_MUSHROOM, Items.RED_MUSHROOM, Items.BROWN_MUSHROOM_BLOCK, Items.RED_MUSHROOM_BLOCK,
        )

        /** A bee's numbers: light, and quicker in the air than on its feet. */
        fun createAttributes(): AttributeSupplier.Builder = createAnimalAttributes()
            .add(Attributes.MAX_HEALTH, HEALTH)
            .add(Attributes.FLYING_SPEED, FLYING_SPEED)
            .add(Attributes.MOVEMENT_SPEED, WALKING_SPEED)

        /**
         * How long one meal keeps it fed: half a day, so the meal it takes at dawn runs out about dusk and it
         * eats twice a day (Jonah, 2026-09-30). With [GrazedTorchflowerBlock]'s regrowth this decides how
         * many flowers a colony needs, and it is for playtest to tune.
         */
        const val MEAL = 12000

        /** How long a mushroom quickens it, and by how much. */
        private const val MUSHROOM_BOOST = 2400
        private val MUSHROOM_SPEED_ID: Identifier = "scarab_mushroom_quickening".location()
        private val MUSHROOM_SPEED = AttributeModifier(
            MUSHROOM_SPEED_ID, 0.4, AttributeModifier.Operation.ADD_MULTIPLIED_BASE,
        )

        /** Half a day in the world: long enough to follow one, short enough that it is plainly not staying. */
        private const val STRAY_LIFETIME = 12000

        private const val STAY_OUT_AFTER_WAKING = 400
        private const val CHECK_THE_NEST_EVERY = 20

        private const val HEALTH = 8.0
        private const val FLYING_SPEED = 0.6
        private const val WALKING_SPEED = 0.3

        private const val CRUISING = 1.0
        private const val TEMPTED_SPEED = 1.25
        private const val FOLLOWING_SPEED = 1.25

        /** Close enough to steer straight at a point rather than pathfind to it. */
        private const val HOVERING_REACH = 2.0

        /** How far one path is asked to run; past this it goes a step at a time. */
        private const val PATHFINDING_REACH = 16.0
        private const val REPATH_EVERY = 10

        /**
         * How far a path's search may range, which is not how far a path is asked to run: the bee's own, so a
         * scarab under a canopy can find the way round it to a pillar a few blocks off.
         */
        private const val PATH_SEARCH_REACH = 48.0f
        private const val STEP_ACROSS = 8
        private const val STEP_UP = 6
        private const val STEP_SPREAD = PI / 10
        private const val CLIMB = 4
        private const val CLIMB_THRESHOLD = 2.0

        private const val MAX_TURN_DEGREES = 20
        private const val IMPASSABLE = -1.0f
        private const val SHY_OF_WATER = 16.0f
        private const val PREFERS_AIR = 10.0f
        private const val TICKS_PER_FLAP = 2
        private const val LIFT_OUT_OF_LIQUID = 0.01

        private const val QUIET = 0.4f
        private const val EATING_VOLUME = 0.5f
        private const val EATING_PITCH = 1.4f
        private const val PUFFS = 8
        private const val PUFF_SPREAD = 0.2

        /**
         * The nest first, then pairing, then food; an empty nest before work, work before looking further
         * afield, and idling last.
         */
        private const val ROOSTING = 1
        private const val BREEDING = 2
        private const val TEMPTED = 3
        private const val GRAZING = 4
        private const val CLAIMING = 5
        private const val WORKING = 6
        private const val FOLLOWING = 7
        private const val SEEKING = 8
        private const val WANDERING = 9
        private const val FLOATING = 10

        private const val HOME_KEY = "home"
        private const val LAST_MEAL_KEY = "last_meal"
        private const val CARRIED_KEY = "carried"
        private const val COLONY_KEY = "belongs_to_a_colony"
        private const val SATIETY_KEY = "satiety"
        private const val MUSHROOM_KEY = "mushroom_fed_for"
        private const val STRAY_KEY = "stray_for"
    }
}

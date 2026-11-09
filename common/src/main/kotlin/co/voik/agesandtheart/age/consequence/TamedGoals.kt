package co.voik.agesandtheart.age.consequence

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.FluidTags
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.behavior.BlockPosTracker
import net.minecraft.world.entity.ai.behavior.EntityTracker
import net.minecraft.world.entity.ai.control.FlyingMoveControl
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.goal.target.TargetGoal
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.entity.ai.memory.WalkTarget
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation
import net.minecraft.world.entity.ai.navigation.WaterBoundPathNavigation
import net.minecraft.world.entity.ai.targeting.TargetingConditions
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.entity.animal.AgeableWaterCreature
import net.minecraft.world.entity.animal.fish.WaterAnimal
import net.minecraft.world.entity.animal.squid.Squid
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.monster.Blaze
import net.minecraft.world.entity.monster.Ghast
import net.minecraft.world.entity.monster.Phantom
import net.minecraft.world.entity.monster.Vex
import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import java.util.EnumSet

/**
 * What a `tamable` creature is given: [Tempted] while it is wild, and [FollowsOwner] and [DefendsOwner] once it is
 * tamed (`Taming`). Each moves the creature through [Steering], which knows the several ways vanilla's creatures move.
 */
object TamedGoals {

    /** A wild creature its Age wrote tamable, given a way to be tempted where anything tempts it. */
    fun forTamable(mob: Mob) {
        val alreadyGiven = mob.goalSelector.availableGoals.any { it.goal is Tempted }
        if (!alreadyGiven) mob.goalSelector.addGoal(MOVING_FIRST, Tempted(mob))
    }

    /** A creature somebody tamed, wherever it is: it follows and fights for them, or holds where it was told. */
    fun forTamed(mob: Mob) {
        mob.goalSelector.removeAllGoals { it is Tempted }
        val alreadyGiven = mob.goalSelector.availableGoals.any { it.goal is FollowsOwner }
        if (alreadyGiven) return
        mob.setPersistenceRequired()
        if (mob is Vex) mob.setLimitedLife(Int.MAX_VALUE)
        if (mob is PathfinderMob) Dispositions.armIfUnarmed(mob)
        mob.goalSelector.addGoal(MOVING_FIRST, FollowsOwner(mob))
        mob.goalSelector.addGoal(MOVING_FIRST, HoldsPosition(mob))
        mob.targetSelector.addGoal(MOVING_FIRST, DefendsOwner(mob))
    }

    /** Coming to a player holding something it wants, as an animal comes to its food. */
    class Tempted(private val mob: Mob) : Goal() {
        private var player: Player? = null

        init {
            flags = EnumSet.of(Flag.MOVE, Flag.LOOK)
        }

        override fun canUse(): Boolean {
            player = nearestTempting()
            return player != null
        }

        override fun canContinueToUse(): Boolean = canUse()

        override fun stop() {
            player = null
            Steering.halt(mob)
        }

        override fun tick() {
            val tempting = player ?: return
            if (mob.distanceTo(tempting) < Steering.closeEnough(mob)) Steering.halt(mob) else Steering.toward(mob, tempting, TEMPTED)
            mob.lookControl.setLookAt(tempting)
        }

        private fun nearestTempting(): Player? {
            val level = mob.level() as? ServerLevel ?: return null
            val temptations = Temptations.of(level.server)
            fun holdsWhatItWants(player: Player): Boolean =
                temptations.tempts(mob, player.mainHandItem) || temptations.tempts(mob, player.offhandItem)
            return level.players()
                .filter { !it.isSpectator && it.distanceTo(mob) <= TEMPTED_FROM && holdsWhatItWants(it) }
                .minByOrNull { it.distanceToSqr(mob) }
        }
    }

    /** Keeping near its owner, and brought to them where it falls far behind; never while it fights or holds. */
    class FollowsOwner(private val mob: Mob) : Goal() {
        private var owner: Player? = null

        init {
            flags = EnumSet.of(Flag.MOVE, Flag.LOOK)
        }

        override fun canUse(): Boolean {
            owner = ownerToFollow() ?: return false
            return mob.distanceTo(owner ?: return false) > FOLLOWS_FROM
        }

        override fun canContinueToUse(): Boolean {
            val following = ownerToFollow() ?: return false
            return mob.distanceTo(following) > Steering.closeEnough(mob)
        }

        override fun stop() {
            owner = null
            Steering.halt(mob)
        }

        override fun tick() {
            val following = owner ?: return
            mob.lookControl.setLookAt(following)
            val hasFallenFarBehind = mob.distanceTo(following) > BROUGHT_FROM
            if (hasFallenFarBehind) Steering.bringNear(mob, following) else Steering.toward(mob, following, FOLLOWING)
        }

        private fun ownerToFollow(): Player? {
            val owner = Taming.ownerIn(mob) ?: return null
            val isHeldBack = Taming.isHolding(mob) || mob.isPassenger || mob.isLeashed || owner.isSpectator
            return owner.takeIf { !isHeldBack && mob.target == null }
        }
    }

    /**
     * Staying where its owner told it to, and going back there after a fight. A bat told to hold under a ceiling hangs
     * from it whenever nobody is near enough to wake it, and anywhere else circles the spot.
     */
    class HoldsPosition(private val mob: Mob) : Goal() {
        private var spot: BlockPos? = null

        init {
            flags = EnumSet.of(Flag.MOVE, Flag.JUMP)
        }

        override fun canUse(): Boolean {
            spot = Taming.heldAt(mob)
            return spot != null && mob.target == null
        }

        override fun canContinueToUse(): Boolean = canUse()

        override fun stop() {
            spot = null
        }

        override fun tick() {
            val holding = spot ?: return
            if (mob is Bat) return Steering.roostOrCircle(mob, holding)
            val at = Vec3.atBottomCenterOf(holding)
            val isAway = mob.position().distanceTo(at) > HOLDS_WITHIN
            if (isAway || Steering.isRunByABrain(mob)) Steering.toward(mob, at, HOLDING) else Steering.halt(mob)
        }
    }

    /** Going for whatever its owner is fighting, or whatever is fighting them. */
    class DefendsOwner(mob: Mob) : TargetGoal(mob, false) {
        private var foe: LivingEntity? = null

        override fun canUse(): Boolean {
            if (Taming.isHolding(mob)) return false
            val owner = Taming.ownerIn(mob) ?: return false
            val candidate = owner.lastHurtByMob ?: owner.lastHurtMob ?: return false
            val isNew = candidate !== mob.target && candidate !== mob
            foe = candidate.takeIf { isNew && canAttack(it, TargetingConditions.DEFAULT) }
            return foe != null
        }

        override fun start() {
            val chosen = foe ?: return
            mob.target = chosen
            if (mob.brain.checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED)) {
                mob.brain.setMemory(MemoryModuleType.ATTACK_TARGET, chosen)
            }
            super.start()
        }
    }

    /** Moving a creature toward something, by whichever of vanilla's ways it moves. */
    object Steering {
        fun toward(mob: Mob, target: Entity, speed: Double) {
            when {
                isRunByABrain(mob) -> {
                    mob.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(target, speed.toFloat(), BRAIN_CLOSE_ENOUGH))
                    mob.brain.setMemory(MemoryModuleType.LOOK_TARGET, EntityTracker(target, true))
                }
                mob is Phantom -> mob.anchorPoint = target.blockPosition().above(PHANTOM_CIRCLES_ABOVE)
                mob.isMovedByNavigation() -> mob.navigation.moveTo(target, speed)
                else -> toward(mob, Vec3(target.x, target.eyeY, target.z), speed)
            }
        }

        fun toward(mob: Mob, at: Vec3, speed: Double) {
            val block = BlockPos.containing(at)
            when {
                isRunByABrain(mob) -> {
                    mob.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(block, speed.toFloat(), ON_THE_SPOT))
                    mob.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(block))
                }
                mob is Phantom -> mob.anchorPoint = block
                mob is Bat -> mob.targetPosition = block
                mob is Squid -> mob.movementVector = at.subtract(mob.position()).normalize().scale(SQUID_SWIMS)
                mob is AbstractCubeMob -> {
                    val control = mob.moveControl as? AbstractCubeMob.CubeMobMoveControl<*> ?: return
                    control.setDirection(yawToward(mob, at), false)
                    control.setWantedMovement(speed)
                }
                mob.isMovedByNavigation() -> mob.navigation.moveTo(at.x, at.y, at.z, speed)
                else -> mob.moveControl.setWantedPosition(at.x, at.y, at.z, speed)
            }
        }

        /**
         * Where a bat held at its own position can hang: under the nearest block a bat hangs from, a little above it
         * and around, or nowhere.
         */
        fun roostAbove(bat: Bat): BlockPos? {
            val level = bat.level()
            val from = bat.blockPosition()
            val candidates = buildList {
                for (awayX in -ROOST_SPREAD..ROOST_SPREAD) for (awayZ in -ROOST_SPREAD..ROOST_SPREAD) {
                    for (up in 0..ROOST_REACH) add(from.offset(awayX, up, awayZ))
                }
            }
            fun canHangUnder(spot: BlockPos): Boolean =
                level.isEmptyBlock(spot) && level.getBlockState(spot.above()).isRedstoneConductor(level, spot.above())
            return candidates.filter(::canHangUnder).minByOrNull { it.distSqr(from) }
        }

        /**
         * A held bat hangs from its roost when nobody is near enough to wake it, and otherwise flutters about it. It
         * clings once it is within reach, since a bat gives up on any spot it comes within two blocks of.
         */
        fun roostOrCircle(bat: Bat, spot: BlockPos) {
            if (bat.isResting) return
            val level = bat.level()
            val hasARoost = level.getBlockState(spot.above()).isRedstoneConductor(level, spot.above())
            val isWithinReach = bat.position().distanceTo(Vec3.atCenterOf(spot)) < BAT_CLINGS_FROM
            val wouldBeWoken = level.getNearestPlayer(bat, BAT_WAKES_WITHIN) != null
            if (hasARoost && isWithinReach && !wouldBeWoken) {
                bat.setPos(spot.x + HALF_A_BLOCK, spot.y + 1.0 - bat.bbHeight, spot.z + HALF_A_BLOCK)
                bat.deltaMovement = Vec3.ZERO
                bat.isResting = true
                return
            }
            val circling = bat.targetPosition
            val isCircling = circling != null && circling.closerThan(spot, BAT_CIRCLES_WITHIN.toDouble())
            if (hasARoost && !wouldBeWoken) {
                bat.targetPosition = spot
            } else if (!isCircling) {
                val random = bat.random
                fun around() = random.nextIntBetweenInclusive(-BAT_CIRCLES_WITHIN, BAT_CIRCLES_WITHIN)
                bat.targetPosition = spot.offset(around(), random.nextIntBetweenInclusive(-1, 1), around())
            }
        }

        fun halt(mob: Mob) {
            when {
                isRunByABrain(mob) -> mob.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
                mob is Squid -> mob.movementVector = Vec3.ZERO
                mob is Phantom || mob is Bat || mob is AbstractCubeMob -> Unit
                mob is Vex || mob !is PathfinderMob -> mob.moveControl.setWait()
                else -> mob.navigation.stop()
            }
        }

        /** How near is near enough to stop, which a ghast reaches a long way off. */
        fun closeEnough(mob: Mob): Double = STOPS_WITHIN + mob.bbWidth

        /** Put down close beside [owner], as a wolf is: somewhere it can stand, swim or float. */
        fun bringNear(mob: Mob, owner: Player) {
            val random = mob.random
            repeat(BRINGING_TRIES) {
                val awayX = random.nextIntBetweenInclusive(-BRINGING_SPREAD, BRINGING_SPREAD)
                val awayZ = random.nextIntBetweenInclusive(-BRINGING_SPREAD, BRINGING_SPREAD)
                val isOutOfTheOwnersWay = Math.abs(awayX) >= BRINGING_GAP || Math.abs(awayZ) >= BRINGING_GAP
                if (!isOutOfTheOwnersWay) return@repeat
                val spot = owner.blockPosition().offset(awayX, random.nextIntBetweenInclusive(-1, 1), awayZ)
                if (canBePutAt(mob, spot)) {
                    mob.snapTo(spot.x + HALF_A_BLOCK, spot.y.toDouble(), spot.z + HALF_A_BLOCK, mob.yRot, mob.xRot)
                    halt(mob)
                    return
                }
            }
        }

        private fun canBePutAt(mob: Mob, spot: BlockPos): Boolean {
            val level = mob.level()
            val moved = mob.boundingBox.move(spot.x + HALF_A_BLOCK - mob.x, spot.y - mob.y, spot.z + HALF_A_BLOCK - mob.z)
            if (!level.noCollision(mob, moved)) return false
            val isWater = level.getFluidState(spot).`is`(FluidTags.WATER)
            return when {
                livesInWater(mob) -> isWater
                canFly(mob) -> true
                else -> !isWater && level.getBlockState(spot.below()).isFaceSturdy(level, spot.below(), Direction.UP)
            }
        }

        fun isRunByABrain(mob: Mob): Boolean = mob.brain.checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED)

        /** Whether [this] goes where its navigation takes it, which the creatures steered some other way do not. */
        private fun Mob.isMovedByNavigation(): Boolean {
            val isSteeredItsOwnWay = this is Bat || this is Squid || this is AbstractCubeMob || this is Vex
            return this is PathfinderMob && !isSteeredItsOwnWay
        }

        private fun yawToward(mob: Mob, at: Vec3): Float =
            (Mth.atan2(at.z - mob.z, at.x - mob.x) * DEGREES_PER_RADIAN).toFloat() - QUARTER_TURN

        private fun livesInWater(mob: Mob): Boolean =
            mob is WaterAnimal || mob is AgeableWaterCreature || mob.navigation is WaterBoundPathNavigation

        private fun canFly(mob: Mob): Boolean {
            val fliesOfItself = mob is Ghast || mob is Phantom || mob is Bat || mob is Vex || mob is Blaze || mob is WitherBoss
            return fliesOfItself || mob.moveControl is FlyingMoveControl || mob.navigation is FlyingPathNavigation
        }
    }

    /** Ahead of a creature's own wandering and attacks, which are vanilla's from one down. */
    private const val MOVING_FIRST = 1

    private const val TEMPTED_FROM = 10.0
    private const val TEMPTED = 1.0
    private const val FOLLOWING = 1.0

    /** A wolf's distances: it sets off ten blocks behind and is brought along at twelve. */
    private const val FOLLOWS_FROM = 10.0
    private const val BROUGHT_FROM = 12.0
    private const val STOPS_WITHIN = 2.5

    private const val BRAIN_CLOSE_ENOUGH = 2
    private const val ON_THE_SPOT = 0
    private const val HOLDING = 1.0
    private const val HOLDS_WITHIN = 1.5

    private const val ROOST_REACH = 8
    private const val ROOST_SPREAD = 2
    private const val BAT_WAKES_WITHIN = 4.0
    private const val BAT_CLINGS_FROM = 2.5
    private const val BAT_CIRCLES_WITHIN = 3

    private const val DEGREES_PER_RADIAN = 180.0 / Math.PI
    private const val QUARTER_TURN = 90.0f
    private const val PHANTOM_CIRCLES_ABOVE = 8
    private const val SQUID_SWIMS = 0.2

    private const val BRINGING_TRIES = 10
    private const val BRINGING_SPREAD = 3
    private const val BRINGING_GAP = 2
    private const val HALF_A_BLOCK = 0.5
}

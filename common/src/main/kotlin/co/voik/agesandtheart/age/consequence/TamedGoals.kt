package co.voik.agesandtheart.age.consequence

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.FluidTags
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.PathfinderMob
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

    /** A creature somebody tamed, wherever it is: it follows and fights for them, and stays. */
    fun forTamed(mob: Mob) {
        mob.goalSelector.removeAllGoals { it is Tempted }
        val alreadyGiven = mob.goalSelector.availableGoals.any { it.goal is FollowsOwner }
        if (alreadyGiven) return
        mob.setPersistenceRequired()
        if (mob is Vex) mob.setLimitedLife(Int.MAX_VALUE)
        if (mob is PathfinderMob) Dispositions.armIfUnarmed(mob)
        mob.goalSelector.addGoal(MOVING_FIRST, FollowsOwner(mob))
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

    /** Keeping near its owner, and brought to them where it falls far behind; never while it fights or stays. */
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
            val isHeldBack = Taming.isStaying(mob) || mob.isPassenger || mob.isLeashed || owner.isSpectator
            return owner.takeIf { !isHeldBack && mob.target == null }
        }
    }

    /** Going for whatever its owner is fighting, or whatever is fighting them. */
    class DefendsOwner(mob: Mob) : TargetGoal(mob, false) {
        private var foe: LivingEntity? = null

        override fun canUse(): Boolean {
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
                mob is Bat -> mob.targetPosition = target.blockPosition().above()
                mob is Squid -> mob.movementVector = target.position().subtract(mob.position()).normalize().scale(SQUID_SWIMS)
                mob is AbstractCubeMob -> {
                    mob.lookAt(target, CUBE_TURNS, CUBE_TURNS)
                    val control = mob.moveControl as? AbstractCubeMob.CubeMobMoveControl<*> ?: return
                    control.setDirection(mob.yRot, false)
                    control.setWantedMovement(speed)
                }
                mob is Vex || mob !is PathfinderMob -> mob.moveControl.setWantedPosition(target.x, target.eyeY, target.z, speed)
                else -> mob.navigation.moveTo(target, speed)
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

        private fun isRunByABrain(mob: Mob): Boolean = mob.brain.checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED)

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
    private const val PHANTOM_CIRCLES_ABOVE = 8
    private const val SQUID_SWIMS = 0.2
    private const val CUBE_TURNS = 10.0f

    private const val BRINGING_TRIES = 10
    private const val BRINGING_SPREAD = 3
    private const val BRINGING_GAP = 2
    private const val HALF_A_BLOCK = 0.5
}

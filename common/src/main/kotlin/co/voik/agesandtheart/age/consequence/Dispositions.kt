package co.voik.agesandtheart.age.consequence

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Spawns
import co.voik.agesandtheart.generation.Ages
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal
import net.minecraft.world.entity.ai.goal.OcelotAttackGoal
import net.minecraft.world.entity.ai.goal.PanicGoal
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.monster.RangedAttackMob
import net.minecraft.world.entity.player.Player
import java.util.WeakHashMap

/**
 * How a book wrote its creatures to take to everything else, member by member (`Spawns.DISPOSITION`): `friendly` never
 * fights, `passive` never starts a fight and fights only what hit it, `aggressive` goes for players on sight.
 *
 * Friendly and passive are one answer to the question every way of taking a target asks first, whether the creature
 * may attack this one (`mixin/CanAttackMixin`). Aggressive is laid on as the creature enters the Age, as
 * [WrittenCreatures] lays on its pace and strength, and is transient like them.
 */
object Dispositions {

    enum class Disposition { OWN, FRIENDLY, PASSIVE, AGGRESSIVE }

    /** Whether [attacker] may not go for [target]: never where it is friendly, and only what hit it where it is passive. */
    @JvmStatic
    fun forbids(attacker: LivingEntity, target: LivingEntity?): Boolean {
        if (target == null) return false
        return when (of(attacker)) {
            Disposition.FRIENDLY -> true
            Disposition.PASSIVE -> target !== attacker.lastHurtByMob
            Disposition.OWN, Disposition.AGGRESSIVE -> false
        }
    }

    /** [entity]'s disposition as its Age wrote it, or its own anywhere else and for anything but a creature. */
    fun of(entity: LivingEntity): Disposition {
        if (entity !is Mob) return Disposition.OWN
        val level = entity.level() as? ServerLevel ?: return Disposition.OWN
        val byKind = writtenIn(level) ?: return Disposition.OWN
        return byKind.getOrPut(entity.type) { read(level, entity.type) }
    }

    /**
     * What each kind of creature in [level] was written, read once a kind: [forbids] is asked of every candidate in
     * every target search. Null outside an Age. Weak on the level, so a closed Age lets its answers go.
     */
    private fun writtenIn(level: ServerLevel): MutableMap<EntityType<*>, Disposition>? {
        if (Ages.recipeOf(level) == null) return null
        return synchronized(READ) { READ.getOrPut(level) { HashMap() } }
    }

    private val READ = WeakHashMap<ServerLevel, MutableMap<EntityType<*>, Disposition>>()

    private fun read(level: ServerLevel, type: EntityType<*>): Disposition {
        val spawns = Ages.recipeOf(level)?.composition?.optionsFor(Aspect.SPAWNS) ?: return Disposition.OWN
        val kind = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString()
        return when (spawns.of(Spawns.DISPOSITION, member = kind)) {
            Spawns.FRIENDLY -> Disposition.FRIENDLY
            Spawns.PASSIVE -> Disposition.PASSIVE
            Spawns.AGGRESSIVE -> Disposition.AGGRESSIVE
            else -> Disposition.OWN
        }
    }

    /**
     * An aggressive creature entering its Age: it goes for the nearest player and stops running from anything, and
     * one with no attack of its own is given a feeble [Headbutt]. A creature run by a brain rather than goals keeps
     * its own ways but for the target, since the two would fight over where it walks.
     */
    fun loaded(entity: Entity) {
        val mob = entity as? PathfinderMob ?: return
        if (of(mob) != Disposition.AGGRESSIVE) return
        val alreadyGiven = mob.targetSelector.availableGoals.any { it.goal is GoesForPlayers }
        if (alreadyGiven) return
        val isRunByGoals = mob.goalSelector.availableGoals.isNotEmpty()
        if (isRunByGoals && !fightsAlready(mob)) {
            mob.goalSelector.removeAllGoals { it is PanicGoal || it is AvoidEntityGoal<*> }
            mob.goalSelector.addGoal(HEADBUTTING, Headbutt(mob))
            mob.targetSelector.addGoal(HITTING_BACK, HurtByTargetGoal(mob))
        }
        mob.targetSelector.addGoal(GOING_FOR_PLAYERS, GoesForPlayers(mob))
    }

    /** Whether [mob] has an attack of its own to make once it has a target. */
    private fun fightsAlready(mob: PathfinderMob): Boolean {
        val hasAnAttackGoal = mob.goalSelector.availableGoals.any { it.goal is MeleeAttackGoal || it.goal is OcelotAttackGoal }
        return mob is Enemy || mob is RangedAttackMob || hasAnAttackGoal
    }

    /** Going for the nearest player it can see, as a zombie does. */
    private class GoesForPlayers(mob: PathfinderMob) : NearestAttackableTargetGoal<Player>(mob, Player::class.java, true)

    /** A butt or a bite, for show more than harm, from a creature with no attack of its own. */
    private class Headbutt(mob: PathfinderMob) : MeleeAttackGoal(mob, CHARGING, true) {
        override fun checkAndPerformAttack(target: LivingEntity) {
            if (!canPerformAttack(target)) return
            val level = mob.level() as? ServerLevel ?: return
            resetAttackCooldown()
            mob.swingForAttack(InteractionHand.MAIN_HAND)
            target.hurtServer(level, mob.damageSources().mobAttack(mob), FEEBLE)
        }
    }

    /** Half a heart: a headbutt is a creature making its feelings known. */
    private const val FEEBLE = 1.0f

    /** A charge a little quicker than the creature's own walk. */
    private const val CHARGING = 1.25

    private const val HEADBUTTING = 1
    private const val HITTING_BACK = 1
    private const val GOING_FOR_PLAYERS = 2
}

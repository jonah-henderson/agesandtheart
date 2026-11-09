package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import co.voik.agesandtheart.age.consequence.Dispositions
import co.voik.agesandtheart.age.consequence.Taming
import co.voik.agesandtheart.age.consequence.Temptations
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.boss.enderdragon.EnderDragon
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import java.util.Arrays

/**
 * **A dragon that arrived in an Age rather than in the End**, told where it is.
 *
 * `EnderDragon` circles and lands at its `fightOrigin`, which defaults to the world origin — so a dragon
 * written into an Age would fly off to 0, 0 and hold a pattern over ground nobody is standing on. Told its
 * own position instead, it circles where it was found, which is the whole of what a dragon is for.
 *
 * **Nothing else about it needs a thing.** Every use of the fight is null-guarded in vanilla: with none, it
 * counts no crystals so it never heals, it drops five hundred experience when it dies, and it leaves no egg
 * and opens no portal — those being the End's furniture rather than the dragon's. So an Age's dragon is a
 * dragon you can actually kill, which is the version worth writing.
 *
 * Done on the tick rather than at spawn because there is no seam at spawn that both loaders share, and
 * because this way it catches a dragon however it arrived — written, summoned, or carried in.
 */
object Dragons {
    /** Every dragon in [level] that still thinks the fight is at the world origin, told otherwise. */
    fun findTheirOwnGround(level: ServerLevel) {
        for (dragon in level.getEntities(EntityTypes.ENDER_DRAGON) { lost(it) }) {
            dragon.fightOrigin = dragon.blockPosition()
        }
    }

    /**
     * Each dragon its Age wrote tamable, or somebody tamed, steered by what it wants and whom it serves. A dragon
     * runs no goals, only its phases, so this does for it what `TamedGoals` does for every other creature: its hover
     * phase is pointed at where it should be, and its strafe at whatever it should fight.
     */
    fun answerToTheirOwners(level: ServerLevel) {
        for (dragon in level.getEntities(EntityTypes.ENDER_DRAGON) { it.isAlive }) {
            when {
                Taming.isTamed(dragon) -> served(dragon)
                Dispositions.of(dragon) == Dispositions.Disposition.TAMABLE -> drawnToItsEgg(dragon, level)
            }
        }
    }

    /** What a dragon's head and wings strike, less what its disposition says it may not attack. */
    @JvmStatic
    fun sparedBy(dragon: EnderDragon, struck: List<Entity>): List<Entity> =
        struck.filterNot { it is LivingEntity && Dispositions.forbids(dragon, it) }

    /** Whether a dragon passes through blocks rather than breaking them: one somebody tamed does. */
    @JvmStatic
    fun leavesTheGroundBe(dragon: EnderDragon): Boolean = Taming.isTamed(dragon)

    /**
     * A tamed dragon fights what its owner fights, and otherwise hovers above them, or where they told it to hold. Its
     * flight graph is laid again around it when it has come far from where it was laid, since its strafe flies to
     * those nodes before turning on its target.
     */
    private fun served(dragon: EnderDragon) {
        keepItsNodesNear(dragon)
        val phase = dragon.phaseManager.currentPhase.phase
        if (phase == EnderDragonPhase.STRAFE_PLAYER || phase == EnderDragonPhase.DYING) return
        val heldAt = Taming.heldAt(dragon)
        val foe = if (heldAt == null) foeOf(dragon) else null
        if (foe != null) {
            dragon.phaseManager.setPhase(EnderDragonPhase.STRAFE_PLAYER)
            dragon.phaseManager.getPhase(EnderDragonPhase.STRAFE_PLAYER).setTarget(foe)
            return
        }
        val aboveTheOwner = Taming.ownerIn(dragon)?.position()?.add(0.0, ABOVE_ITS_OWNER, 0.0)
        hoverAt(dragon, heldAt?.let(Vec3::atCenterOf) ?: aboveTheOwner ?: dragon.position())
    }

    private fun foeOf(dragon: EnderDragon): LivingEntity? {
        val owner = Taming.ownerIn(dragon)
        fun isWorthFighting(foe: LivingEntity): Boolean {
            val isInReach = foe.level() === dragon.level() && foe.distanceTo(dragon) <= STRIKES_WITHIN
            return foe.isAlive && foe !== dragon && isInReach && Taming.mayFight(dragon, foe)
        }
        return listOfNotNull(dragon.lastHurtByMob, owner?.lastHurtByMob, owner?.lastHurtMob).firstOrNull(::isWorthFighting)
    }

    /**
     * A wild tamable dragon comes down to hover within reach of the nearest player holding what it wants, and goes back
     * to circling when nobody is.
     */
    private fun drawnToItsEgg(dragon: EnderDragon, level: ServerLevel) {
        val temptations = Temptations.of(level.server)
        fun holdsWhatItWants(player: Player) = temptations.tempts(dragon, player.mainHandItem) || temptations.tempts(dragon, player.offhandItem)
        val tempter = level.players()
            .filter { !it.isSpectator && it.distanceTo(dragon) <= DRAWN_FROM && holdsWhatItWants(it) }
            .minByOrNull { it.distanceToSqr(dragon) }
        if (tempter != null) {
            dragon.addTag(DRAWN_TAG)
            hoverAt(dragon, tempter.position().add(0.0, tempter.eyeHeight + WITHIN_REACH_ABOVE, 0.0))
        } else if (dragon.removeTag(DRAWN_TAG)) {
            dragon.phaseManager.setPhase(EnderDragonPhase.HOLDING_PATTERN)
        }
    }

    private fun hoverAt(dragon: EnderDragon, spot: Vec3) {
        if (dragon.phaseManager.currentPhase.phase != EnderDragonPhase.HOVERING) dragon.phaseManager.setPhase(EnderDragonPhase.HOVERING)
        dragon.phaseManager.getPhase(EnderDragonPhase.HOVERING).targetLocation = spot
    }

    private fun keepItsNodesNear(dragon: EnderDragon) {
        val hasStrayed = !dragon.fightOrigin.closerToCenterThan(dragon.position(), NODES_LAID_AGAIN_BEYOND)
        if (!hasStrayed) return
        dragon.fightOrigin = dragon.blockPosition()
        Arrays.fill(dragon.nodes, null)
    }

    /**
     * Whether this dragon has never been told where it is. `BlockPos.ZERO` is the field's own default and
     * no Age puts a fight there, so it doubles as "not yet ours" with nothing to persist.
     */
    private fun lost(dragon: EnderDragon): Boolean = dragon.fightOrigin == BlockPos.ZERO

    /** Marks a wild dragon this put to hovering, so that one hovering of its own accord is left to it. */
    private const val DRAWN_TAG = "agesandtheart.drawn"

    private const val ABOVE_ITS_OWNER = 12.0
    private const val WITHIN_REACH_ABOVE = 2.0
    private const val DRAWN_FROM = 64.0
    private const val STRIKES_WITHIN = 64.0

    /** Past its outer ring of nodes, which is sixty blocks out. */
    private const val NODES_LAID_AGAIN_BEYOND = 100.0
}

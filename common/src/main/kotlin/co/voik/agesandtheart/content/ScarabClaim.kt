package co.voik.agesandtheart.content

import co.voik.agesandtheart.age.reward.ScarabHabitat
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.phys.Vec3
import java.util.EnumSet

/**
 * A homeless scarab taking an empty nest for its own (design §7.1.2): the nearest one in reach that nobody
 * holds, flown to and claimed at its door. Ahead of work, so the nest a colony has just laid is taken by
 * the scarab that was building towards it.
 */
class ScarabClaim(private val scarab: Scarab) : Goal() {

    private var nest: BlockPos? = null
    private var travelling = 0
    private var lookAgainAfter = 0L

    init {
        flags = EnumSet.of(Flag.MOVE)
    }

    override fun canUse(): Boolean {
        val level = scarab.level() as? ServerLevel ?: return false
        if (scarab.isHoused || level.gameTime < lookAgainAfter) return false
        nest = vacantNestNear(level, scarab.blockPosition())
        if (nest == null) lookAgainAfter = level.gameTime + LOOK_AGAIN_AFTER
        return nest != null
    }

    override fun canContinueToUse(): Boolean {
        val level = scarab.level() as? ServerLevel ?: return false
        val stillVacant = nest?.let { isVacant(level, it) } == true
        return !scarab.isHoused && stillVacant && travelling < GIVES_UP_AFTER
    }

    override fun start() {
        travelling = 0
    }

    override fun stop() {
        nest = null
        scarab.navigation.stop()
    }

    override fun requiresUpdateEveryTick(): Boolean = true

    override fun tick() {
        val level = scarab.level() as? ServerLevel ?: return
        val at = nest ?: return
        val claimed = level.getBlockEntity(at) as? ScarabNestBlockEntity ?: return
        travelling++
        val door = Vec3.atBottomCenterOf(claimed.doorOf(level))
        scarab.headFor(door)
        if (!scarab.isNear(door, ARRIVES_WITHIN)) return
        // Asked again on arrival: another may have reached it this same tick.
        if (!claimed.isVacant) return
        claimed.claimFor(scarab)
        scarab.settleIn(at)
    }

    private fun vacantNestNear(level: ServerLevel, from: BlockPos): BlockPos? =
        ScarabHabitat.nestsNear(level, from, NEST_SEARCH).firstOrNull { isVacant(level, it) }

    private fun isVacant(level: ServerLevel, at: BlockPos): Boolean =
        (level.getBlockEntity(at) as? ScarabNestBlockEntity)?.isVacant == true

    private companion object {
        const val NEST_SEARCH = 48
        const val ARRIVES_WITHIN = 1.5
        const val LOOK_AGAIN_AFTER = 100L
        const val GIVES_UP_AFTER = 2400
    }
}

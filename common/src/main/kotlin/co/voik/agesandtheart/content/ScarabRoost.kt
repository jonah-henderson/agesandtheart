package co.voik.agesandtheart.content

import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.phys.Vec3
import java.util.EnumSet

/**
 * A scarab going into its pillar for the night, as a bee goes into its hive (design §7.1.2), by the door
 * beside the chamber ([ScarabNestBlockEntity.doorOf]).
 */
class ScarabRoost(private val scarab: Scarab) : Goal() {

    private var travelling = 0
    private var retryAfter = 0L

    init {
        flags = EnumSet.of(Flag.MOVE)
    }

    override fun canUse(): Boolean {
        val isRested = scarab.level().gameTime >= retryAfter
        return isRested && scarab.wantsToRoost() && nestWithRoom() != null
    }

    override fun canContinueToUse(): Boolean = canUse() && travelling < GIVES_UP_AFTER

    override fun start() {
        travelling = 0
    }

    override fun stop() {
        scarab.navigation.stop()
        if (travelling >= GIVES_UP_AFTER) retryAfter = scarab.level().gameTime + TRIES_AGAIN_AFTER
    }

    override fun requiresUpdateEveryTick(): Boolean = true

    override fun tick() {
        travelling++
        val nest = nestWithRoom() ?: return
        val door = Vec3.atBottomCenterOf(nest.doorOf(scarab.level()))
        scarab.headFor(door)
        if (scarab.isNear(door, GOES_IN_WITHIN)) nest.admit(scarab)
    }

    private fun nestWithRoom(): ScarabNestBlockEntity? = scarab.nest()?.takeUnless { it.isOccupied }

    private companion object {
        /** Two minutes, after which it tries again later rather than circling its pillar all night. */
        const val GIVES_UP_AFTER = 2400
        const val TRIES_AGAIN_AFTER = 600L
        const val GOES_IN_WITHIN = 1.0
    }
}

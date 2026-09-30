package co.voik.agesandtheart.content

import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.util.AirAndWaterRandomPos
import net.minecraft.world.entity.ai.util.HoverRandomPos
import net.minecraft.world.phys.Vec3
import java.util.EnumSet
import kotlin.math.PI

/**
 * A housed scarab idling — the bee's own wander, drawn back toward the nest once it strays too far from it.
 *
 * A homeless one never gets here: [ScarabSettle] is always running for it, and its roaming is that goal's.
 */
class ScarabWander(private val scarab: Scarab) : Goal() {

    init {
        flags = EnumSet.of(Flag.MOVE)
    }

    override fun canUse(): Boolean = scarab.navigation.isDone && scarab.random.nextInt(ONE_IN_THIS_MANY) == 0

    override fun canContinueToUse(): Boolean = scarab.navigation.isInProgress

    override fun start() {
        val target = somewhereToGo() ?: return
        scarab.navigation.moveTo(target.x, target.y, target.z, CRUISING)
    }

    private fun somewhereToGo(): Vec3? {
        val home = scarab.home
        val strayedFromHome = home != null && !scarab.isNear(Vec3.atCenterOf(home), TETHER)
        val direction = if (home != null && strayedFromHome) {
            Vec3.atCenterOf(home).subtract(scarab.position()).normalize()
        } else {
            scarab.getViewVector(0.0f)
        }
        val overTheGround =
            HoverRandomPos.getPos(scarab, ACROSS, UP, direction.x, direction.z, SPREAD, HOVERS_UP_TO, HOVERS_AT_LEAST)
        return overTheGround
            ?: AirAndWaterRandomPos.getPos(scarab, ACROSS, DOWN_UP, BELOW, direction.x, direction.z, SPREAD_WIDE)
    }

    private companion object {
        const val ONE_IN_THIS_MANY = 10
        const val CRUISING = 1.0

        /** How far from the nest it goes before turning back toward it. */
        const val TETHER = 24.0

        /** The bee's own numbers. */
        const val ACROSS = 8
        const val UP = 7
        const val DOWN_UP = 4
        const val BELOW = -2
        const val HOVERS_UP_TO = 3
        const val HOVERS_AT_LEAST = 1
        const val SPREAD = (PI / 2).toFloat()
        const val SPREAD_WIDE = PI / 2
    }
}

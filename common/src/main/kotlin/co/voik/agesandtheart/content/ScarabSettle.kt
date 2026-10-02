package co.voik.agesandtheart.content

import co.voik.agesandtheart.age.reward.ScarabHabitat
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.phys.Vec3
import java.util.EnumSet

/**
 * A homeless scarab with no nest to claim and no work in reach, looking for somewhere it could have either
 * — the turtle's way home, and the stray's whole behaviour (design §7.1.2).
 *
 * It flies to the nearest colony it can find and searches around it, so **a player can follow one to a
 * colony**. Where there is none, it roams on a held heading, and the claiming and building goals look again
 * as it goes; that is the stray that wanders and never settles, the world saying there is nowhere here good
 * enough. Below work in a scarab's priorities: ground it could begin a pillar on keeps it there.
 *
 * Only the ground is asked, never the Age: a scarab carried anywhere settles wherever it finds a home.
 */
class ScarabSettle(private val scarab: Scarab) : Goal() {

    private var colony: BlockPos? = null
    private var roamingToward: Vec3? = null
    private var travelling = 0
    private var lookAgainAfter = 0L

    init {
        flags = EnumSet.of(Flag.MOVE)
    }

    override fun canUse(): Boolean = !scarab.isHoused

    override fun canContinueToUse(): Boolean = !scarab.isHoused

    override fun start() {
        colony = null
        roamingToward = null
        travelling = 0
    }

    override fun stop() {
        scarab.navigation.stop()
    }

    override fun requiresUpdateEveryTick(): Boolean = true

    override fun tick() {
        val level = scarab.level() as? ServerLevel ?: return
        travelling++
        if (level.gameTime >= lookAgainAfter && colony == null) look(level)
        val going = colony ?: return roam(level)
        val there = Vec3.atBottomCenterOf(going.above())
        scarab.headFor(there)
        val hasArrived = scarab.isNear(there, NEAR_A_COLONY)
        if (hasArrived || travelling > GIVES_UP_AFTER) forget()
    }

    /** The nearest colony not already close by, or nothing, which roams. */
    private fun look(level: ServerLevel) {
        lookAgainAfter = level.gameTime + LOOK_AGAIN_AFTER
        colony = ScarabHabitat.colonyNear(level, scarab.blockPosition(), COLONY_SEARCH)
            ?.takeUnless { scarab.isNear(Vec3.atCenterOf(it), NEAR_A_COLONY) }
        if (colony != null) travelling = 0
    }

    private fun forget() {
        colony = null
        travelling = 0
    }

    /** On a heading held for a while, a few blocks above the ground ahead. */
    private fun roam(level: ServerLevel) {
        val aim = roamingToward
        val needsANewHeading = aim == null || scarab.isNear(aim, ARRIVES_WITHIN) || travelling > ROAMS_FOR
        if (needsANewHeading) {
            roamingToward = aNewHeading(level)
            travelling = 0
            return
        }
        aim?.let(scarab::headFor)
    }

    private fun aNewHeading(level: ServerLevel): Vec3 {
        val random = scarab.random
        val turn = (random.nextFloat() * Mth.TWO_PI).toDouble()
        val x = scarab.x + Mth.sin(turn) * ROAMS_AS_FAR_AS
        val z = scarab.z + Mth.cos(turn) * ROAMS_AS_FAR_AS
        val ground = ScarabHabitat.groundNear(level, Mth.floor(x), Mth.floor(z), scarab.blockY)?.y
            ?: scarab.blockY
        return Vec3(x, (ground + FLIES_ABOVE_THE_GROUND).toDouble(), z)
    }

    private companion object {
        const val COLONY_SEARCH = 128

        /** Close enough to a colony to search around it rather than fly to it. */
        const val NEAR_A_COLONY = 16.0

        const val ARRIVES_WITHIN = 1.5
        const val LOOK_AGAIN_AFTER = 200L
        const val GIVES_UP_AFTER = 2400

        /** A heading is held about a minute and a half, which carries it a long way. */
        const val ROAMS_FOR = 1800
        const val ROAMS_AS_FAR_AS = 64.0
        const val FLIES_ABOVE_THE_GROUND = 4
    }
}

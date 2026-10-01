package co.voik.agesandtheart.content

import co.voik.agesandtheart.age.reward.ScarabHabitat
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.phys.Vec3
import java.util.EnumSet

/**
 * A homeless scarab looking for somewhere to live, and going there — the turtle's way home, and the stray's
 * whole behaviour (design §7.1.2).
 *
 * In order: a nest nobody holds, then ground it could claim itself, then any colony at all, which it flies
 * to and searches around — so **a player can follow one to a colony, or watch it found a new one**. Where
 * none of those is there, it roams on a held heading and looks again as it goes; that is the stray that
 * wanders and never settles, the world saying there is nowhere here good enough.
 *
 * Only the ground is asked, never the Age: a scarab carried anywhere settles wherever it finds a home.
 */
class ScarabSettle(private val scarab: Scarab) : Goal() {

    private sealed interface Destination {
        val at: BlockPos

        /** A nest standing empty, to be taken over as it is. */
        data class VacantNest(override val at: BlockPos) : Destination

        /** Mud a scarab could claim, to be made into a nest. */
        data class Site(override val at: BlockPos) : Destination

        /** Somewhere a colony already lives, to be searched around on arrival. */
        data class Colony(override val at: BlockPos) : Destination
    }

    private var destination: Destination? = null
    private var roamingToward: Vec3? = null
    private var travelling = 0
    private var lookAgainAfter = 0L

    init {
        flags = EnumSet.of(Flag.MOVE)
    }

    override fun canUse(): Boolean = !scarab.isHoused

    override fun canContinueToUse(): Boolean = !scarab.isHoused

    override fun start() {
        destination = null
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
        if (level.gameTime >= lookAgainAfter && destination == null) look(level)
        val going = destination
        if (going == null) return roam(level)
        val there = Vec3.atBottomCenterOf(going.at.above())
        scarab.headFor(there)
        if (travelling > GIVES_UP_AFTER) return forget()
        if (scarab.isNear(there, ARRIVES_WITHIN)) arrive(level, going)
    }

    /** Chooses where to go next, or leaves [destination] empty to roam. */
    private fun look(level: ServerLevel) {
        lookAgainAfter = level.gameTime + LOOK_AGAIN_AFTER
        val here = scarab.blockPosition()
        destination = vacantNestNear(level, here)
            ?: siteNear(level, here)?.let(Destination::Site)
            ?: ScarabHabitat.nestsNear(level, here, COLONY_SEARCH).firstOrNull()
                ?.takeUnless { scarab.isNear(Vec3.atCenterOf(it), NEAR_A_COLONY) }
                ?.let(Destination::Colony)
        if (destination != null) travelling = 0
    }

    private fun arrive(level: ServerLevel, going: Destination) {
        when (going) {
            is Destination.VacantNest -> {
                val nest = level.getBlockEntity(going.at) as? ScarabNestBlockEntity
                if (nest != null && nest.isVacant) settle(nest)
            }
            is Destination.Site -> {
                // Asked again on arrival: another scarab may have claimed beside it on the way.
                if (ScarabHabitat.freeSiteAt(level, going.at.x, going.at.z, going.at.y + 1) == going.at) claim(level, going.at)
            }
            // Searched around on the next look, which is now.
            is Destination.Colony -> lookAgainAfter = 0L
        }
        destination = null
    }

    private fun claim(level: ServerLevel, mud: BlockPos) {
        level.setBlockAndUpdate(mud, AgeContent.SCARAB_NEST_BLOCK.defaultBlockState())
        val nest = level.getBlockEntity(mud) as? ScarabNestBlockEntity ?: return
        settle(nest)
    }

    private fun settle(nest: ScarabNestBlockEntity) {
        nest.claimFor(scarab, scarab.random)
        scarab.settleIn(nest.blockPos)
    }

    private fun forget() {
        destination = null
        travelling = 0
    }

    private fun vacantNestNear(level: ServerLevel, from: BlockPos): Destination? =
        ScarabHabitat.nestsNear(level, from, NEST_SEARCH)
            .firstOrNull { (level.getBlockEntity(it) as? ScarabNestBlockEntity)?.isVacant == true }
            ?.let(Destination::VacantNest)

    /** Claimable ground, sampled at random so the search is a fixed cost and never only the nearest. */
    private fun siteNear(level: ServerLevel, from: BlockPos): BlockPos? {
        val random = scarab.random
        repeat(SITE_SAMPLES) {
            val x = from.x + random.nextIntBetweenInclusive(-SITE_SEARCH, SITE_SEARCH)
            val z = from.z + random.nextIntBetweenInclusive(-SITE_SEARCH, SITE_SEARCH)
            ScarabHabitat.freeSiteAt(level, x, z, from.y)?.let { return it }
        }
        return null
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
        const val NEST_SEARCH = 48
        /**
         * A sample is one heightmap read unless it lands on mud, so a search can afford to be dense: a
         * five-by-five pit within this reach is found about four times in five.
         */
        const val SITE_SEARCH = 32
        const val SITE_SAMPLES = 256
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

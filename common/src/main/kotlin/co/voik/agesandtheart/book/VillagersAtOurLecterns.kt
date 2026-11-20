package co.voik.agesandtheart.book

import com.google.common.collect.ImmutableList
import com.mojang.datafixers.util.Pair
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.behavior.Behavior
import net.minecraft.world.entity.ai.behavior.BehaviorControl
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.level.block.entity.LecternBlockEntity

/**
 * A lectern holding a descriptive or linking book is nobody's job site: a villager does not take one, and one that
 * has gives it up when a book of ours is laid on it. Losing it before trading costs the villager its profession, as
 * vanilla does for any job site lost.
 *
 * Reached from `VillagerLecternsMixin`, which is where vanilla's job-site behaviours are wired.
 */
object VillagersAtOurLecterns {

    /** Whether a villager may take the job site at [pos]. Vanilla's own check is always yes for a job site. */
    @JvmStatic
    fun mayWorkAt(level: ServerLevel, pos: BlockPos): Boolean = !holdsABookOfOurs(level, pos)

    /** [core] with [Leaving] among its first behaviours. */
    @JvmStatic
    fun withLeaving(
        core: ImmutableList<Pair<Int, out BehaviorControl<in Villager>>>,
    ): ImmutableList<Pair<Int, out BehaviorControl<in Villager>>> =
        ImmutableList.builder<Pair<Int, out BehaviorControl<in Villager>>>()
            .addAll(core)
            .add(Pair.of(FIRST_PRIORITY, Leaving()))
            .build()

    /** Unloaded ground answers no, rather than being loaded to be asked. */
    private fun holdsABookOfOurs(level: ServerLevel, pos: BlockPos): Boolean {
        if (!level.isLoaded(pos)) return false
        val lectern = level.getBlockEntity(pos) as? LecternBlockEntity ?: return false
        return LecternBooks.isOurs(lectern.book)
    }

    /** Lets go of a job site, held or only headed for, that a book of ours now lies on. */
    private class Leaving : Behavior<Villager>(
        mapOf(
            MemoryModuleType.JOB_SITE to MemoryStatus.REGISTERED,
            MemoryModuleType.POTENTIAL_JOB_SITE to MemoryStatus.REGISTERED,
        ),
    ) {
        override fun checkExtraStartConditions(level: ServerLevel, body: Villager): Boolean {
            val isItsTurn = (level.gameTime + body.id) % TICKS_BETWEEN_LOOKS == 0L
            return isItsTurn && SITES.any { memory -> siteToLeave(level, body, memory) != null }
        }

        override fun start(level: ServerLevel, body: Villager, timestamp: Long) {
            for (memory in SITES) {
                val pos = siteToLeave(level, body, memory) ?: continue
                body.brain.eraseMemory(memory)
                level.poiManager.release(pos)
                level.debugSynchronizers().updatePoi(pos)
            }
        }

        private fun siteToLeave(level: ServerLevel, body: Villager, memory: MemoryModuleType<GlobalPos>): BlockPos? {
            val site = body.brain.getMemory(memory).orElse(null) ?: return null
            if (site.dimension() != level.dimension()) return null
            return site.pos().takeIf { holdsABookOfOurs(level, it) }
        }
    }

    private val SITES = listOf(MemoryModuleType.JOB_SITE, MemoryModuleType.POTENTIAL_JOB_SITE)

    /** Vanilla's own job-site checks run at priority 0. */
    private const val FIRST_PRIORITY = 0

    /** How often each villager looks, staggered by its id so a village does not look all at once. */
    private const val TICKS_BETWEEN_LOOKS = 20L
}

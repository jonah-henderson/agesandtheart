package co.voik.agesandtheart.content

import co.voik.agesandtheart.generation.Ages
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Unit
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.monster.warden.Warden

/**
 * **A warden an Age brought stays**, where vanilla's goes back into the ground once its dig cooldown lapses with
 * nothing to disturb it. A shrieker's warden still leaves.
 *
 * Only a shrieker's warden is put in the emerging pose, before it joins the level, so it is tagged as it joins and
 * the tag outlasts the pose.
 */
object Wardens {

    /**
     * Marks [entity] as a shrieker's if it is a warden emerging into an Age, and otherwise gives it the cooldown at
     * once, which a warden made without `finalizeSpawn` never had.
     */
    fun loaded(entity: Entity) {
        val warden = entity as? Warden ?: return
        val level = warden.level() as? ServerLevel ?: return
        if (Ages.recipeOf(level) == null) return
        if (warden.hasPose(Pose.EMERGING)) warden.addTag(SUMMONED) else if (noShriekerCalled(warden)) renew(warden)
    }

    /** Renews the dig cooldown of every warden in [level] that no shrieker called. */
    fun keepAboveGround(level: ServerLevel) {
        if (level.gameTime % RENEWED_EVERY_TICKS != 0L) return
        level.getEntities(EntityTypes.WARDEN, ::noShriekerCalled).forEach(::renew)
    }

    private fun noShriekerCalled(warden: Warden) = SUMMONED !in warden.entityTags()

    private fun renew(warden: Warden) {
        warden.brain.setMemoryWithExpiry(MemoryModuleType.DIG_COOLDOWN, Unit.INSTANCE, DIG_COOLDOWN_TICKS)
    }

    private const val SUMMONED = "agesandtheart.summoned_by_shrieker"

    /** Vanilla's own cooldown, from `Warden.finalizeSpawn`. */
    private const val DIG_COOLDOWN_TICKS = 1200L

    /** Well inside the cooldown, so it never lapses between two renewals. */
    private const val RENEWED_EVERY_TICKS = 200L
}

package co.voik.agesandtheart.age.phenomena

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.boss.enderdragon.EnderDragon

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
        for (dragon in level.getEntities(net.minecraft.world.entity.EntityType.ENDER_DRAGON) { lost(it) }) {
            dragon.fightOrigin = dragon.blockPosition()
        }
    }

    /**
     * Whether this dragon has never been told where it is. `BlockPos.ZERO` is the field's own default and
     * no Age puts a fight there, so it doubles as "not yet ours" with nothing to persist.
     */
    private fun lost(dragon: EnderDragon): Boolean = dragon.fightOrigin == BlockPos.ZERO
}

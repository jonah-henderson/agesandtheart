package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.consequence.CrumblingColumn
import net.minecraft.world.entity.AreaEffectCloud
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Interaction
import net.minecraft.world.entity.LightningBolt
import net.minecraft.world.entity.Marker
import net.minecraft.world.entity.boss.enderdragon.EnderDragon
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.decoration.HangingEntity

/**
 * What a phenomenon that moves things can move — a tornado's winds and a sand column's weight (Jonah, 2026-11-16):
 * **everything that can be moved at all**, mobs and players, what was dropped, what fell, what is ridden, thrown, lit
 * or drifting. Left out is only what cannot sensibly go anywhere: the phenomena's own markers, what hangs on a block,
 * the game's technical entities, the two bosses whose own steering and parts a fling breaks, and whatever rides
 * something else, which goes with what it rides.
 */
object Swept {

    fun canBeMoved(entity: Entity): Boolean {
        val isOneOfTheMovers = entity is Tornado || entity is SandColumn || entity is CaveIn || entity is CrumblingColumn ||
            entity is MeteorStorm
        val isFixedOrTechnical = entity is HangingEntity || entity is Marker || entity is Display || entity is Interaction ||
            entity is LightningBolt || entity is AreaEffectCloud
        val isTooGreat = entity is EnderDragon || entity is WitherBoss
        val isHeld = entity.isPassenger || entity.isSpectator
        return !isOneOfTheMovers && !isFixedOrTechnical && !isTooGreat && !isHeld
    }
}

package co.voik.agesandtheart.content

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.level.storage.loot.BuiltInLootTables

/**
 * The blast at the end of an unstable plasma bolt — an ordinary explosion's damage under a type of its own,
 * so that everything it kills drops a head, as a charged creeper's victims do. Unlike the creeper's, a burst
 * gives one for every kill rather than one in all (Jonah).
 */
class PlasmaBurst private constructor(explosion: DamageSource) : DamageSource(explosion.typeHolder()) {

    companion object {
        fun of(level: ServerLevel): PlasmaBurst = PlasmaBurst(level.damageSources().explosion(null, null))

        /** Called for every death; only one a burst caused does anything. Vanilla's own `charged_creeper` table, so a pack's additions come too. */
        fun died(victim: LivingEntity, source: DamageSource) {
            if (source !is PlasmaBurst) return
            val level = victim.level() as? ServerLevel ?: return
            val mayDrop = !victim.isBaby && level.gameRules.get(GameRules.MOB_DROPS)
            if (mayDrop) victim.dropFromLootTable(level, source, false, BuiltInLootTables.CHARGED_CREEPER)
        }
    }
}

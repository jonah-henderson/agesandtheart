package co.voik.agesandtheart.platform

import co.voik.agesandtheart.platform.services.MobSpawning
import net.minecraft.world.DifficultyInstance
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.ServerLevelAccessor
import net.neoforged.neoforge.event.EventHooks

/**
 * Through the hook, so `FinalizeSpawnEvent` is raised and another mod may change what arrives or refuse
 * it. A refusal marks the mob, and `addFreshEntity` declines it afterwards. See [MobSpawning].
 */
class NeoForgeMobSpawning : MobSpawning {
    override fun finish(
        level: ServerLevelAccessor,
        mob: Mob,
        difficulty: DifficultyInstance,
        reason: EntitySpawnReason,
    ) {
        EventHooks.finalizeMobSpawn(mob, level, difficulty, reason, null)
    }
}

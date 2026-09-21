package co.voik.agesandtheart.platform

import co.voik.agesandtheart.platform.services.MobSpawning
import net.minecraft.world.DifficultyInstance
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.ServerLevelAccessor

/** Vanilla's own, which on Fabric is the only one there is. See [MobSpawning]. */
class FabricMobSpawning : MobSpawning {
    override fun finish(
        level: ServerLevelAccessor,
        mob: Mob,
        difficulty: DifficultyInstance,
        reason: EntitySpawnReason,
    ) {
        mob.finalizeSpawn(level, difficulty, reason, null)
    }
}

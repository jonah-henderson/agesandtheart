package co.voik.agesandtheart.platform.services

import net.minecraft.world.DifficultyInstance
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.ServerLevelAccessor

/**
 * Finishing a mob the Age is about to put down — **the last moment anything may have a say in it**.
 *
 * Vanilla's `Mob.finalizeSpawn` is the whole of it on Fabric: it gives the mob its gear, its variant and
 * its difficulty-dependent trimmings. NeoForge deprecates calling it directly and asks callers through
 * `EventHooks.finalizeMobSpawn`, which raises `FinalizeSpawnEvent` first — so another mod can change what
 * arrives, or stop it arriving at all.
 *
 * **Going through the hook is deliberate** (Jonah, 2026-09-20): an Age is already a place made of other
 * mods' content, and a mod that adjusts what spawns has as much business adjusting an Age's creatures as
 * the overworld's. A cancelled spawn needs nothing here — NeoForge marks the mob and vanilla's own
 * `addFreshEntity` then refuses it, so the caller's next line is the same on both loaders.
 */
interface MobSpawning {
    fun finish(level: ServerLevelAccessor, mob: Mob, difficulty: DifficultyInstance, reason: EntitySpawnReason)
}

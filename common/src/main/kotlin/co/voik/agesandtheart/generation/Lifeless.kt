package co.voik.agesandtheart.generation

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.ServerLevelAccessor

/**
 * **An Age where nothing lives makes nothing living, by any path** (Jonah, 2026-10-06).
 *
 * Emptying the spawn lists covers natural spawning and nothing else. Bees fly out of nests on generated
 * trees, structures place their inhabitants as they are built, spawners and creaking hearts summon,
 * thunderstorms lay skeleton traps, and animals breed. Every one of those ends in `addFreshEntity`, on the
 * level or on the region a chunk is generated in, so that is where the refusal is: one rule rather than a
 * plug per path, and a path added later is covered without anyone remembering this.
 *
 * **Fresh only.** A creature that follows a player through a link arrives by teleport, which is not this
 * method, so what is brought in stays.
 */
object Lifeless {
    fun refuses(level: ServerLevelAccessor, entity: Entity): Boolean {
        if (entity !is Mob) return false
        val generator = level.level.chunkSource.generator as? AgeChunkGenerator ?: return false
        return generator.nothingLives
    }
}

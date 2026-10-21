package co.voik.agesandtheart.generation

import co.voik.agesandtheart.age.word.Vocabulary
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.ServerLevelAccessor

/**
 * **What a book struck is never made in its Age, by any path** (Jonah, 2026-10-06 for an Age where nothing lives,
 * 2026-10-21 for any creature struck): `no monsters`, `no zombie`, and everything left out of an `only`.
 *
 * Emptying the spawn lists covers natural spawning and nothing else. A shrieker summons its warden, bees fly out of
 * nests on generated trees, structures place their inhabitants as they are built, spawners and raids summon,
 * thunderstorms lay skeleton traps, and animals breed. Every one of those ends in `addFreshEntity`, on the level or
 * on the region a chunk is generated in, so that is where the refusal is: one rule rather than a plug per path, and
 * a path added later is covered without anyone remembering this. A spawn egg is refused with the rest, since nothing
 * there says how a creature came.
 *
 * Only a creature a book could name: one of the pack's own that has no word cannot have been struck by one.
 *
 * **Fresh only.** A creature that follows a player through a link arrives by teleport, which is not this
 * method, so what is brought in stays.
 */
object StruckCreatures {
    fun refuses(level: ServerLevelAccessor, entity: Entity): Boolean {
        if (entity !is Mob) return false
        val generator = level.level.chunkSource.generator as? AgeChunkGenerator ?: return false
        if (generator.nothingLives) return true
        val creature = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type)
        val aBookCouldNameIt = Vocabulary.of(level.level.server).word(creature) != null
        return aBookCouldNameIt && generator.rungOf(creature, level, entity.blockPosition()) <= NONE_OF_IT
    }

    private const val NONE_OF_IT = 0.0
}

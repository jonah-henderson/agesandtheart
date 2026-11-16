package co.voik.agesandtheart.generation

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.word.Vocabulary
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import co.voik.agesandtheart.age.cataclysm.Cataclysms
import net.minecraft.server.level.WorldGenRegion
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.level.levelgen.Heightmap

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
 * Only a creature a book could reach: one with a word of its own, or one the pack says what it is like, as it does
 * of the villager vanilla files with the golems — one of the pack's own that is neither cannot have been struck.
 *
 * **Fresh only.** A creature that follows a player through a link arrives by teleport, which is not this
 * method, so what is brought in stays.
 */
object StruckCreatures {
    fun refuses(level: ServerLevelAccessor, entity: Entity): Boolean {
        if (entity !is Mob) return false
        val generator = level.level.chunkSource.generator as? AgeChunkGenerator ?: return false
        if (generator.nothingLives) return true
        if (isNearTheSurfaceOfAnEndedAge(level, entity)) return true
        val creature = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type)
        val vocabulary = Vocabulary.of(level.level.server)
        val aBookCouldReachIt = vocabulary.word(creature) != null || vocabulary.describes(Aspect.SPAWNS, creature.toString())
        return aBookCouldReachIt && generator.rungOf(creature, level, entity.blockPosition()) <= NONE_OF_IT
    }

    /**
     * Whether [entity] would come to the surface of an Age an end has struck (design §5.3): a village built after
     * it has burned comes without its villagers, its cats or its golems. Deep enough below, life goes on.
     */
    private fun isNearTheSurfaceOfAnEndedAge(level: ServerLevelAccessor, entity: Entity): Boolean {
        val recipe = Ages.recipeOf(level.level) ?: return false
        if (!Cataclysms.hasEnded(level.level.server, recipe, level.level.gameTime)) return false
        val surface = level.getHeight(surfaceOf(level), entity.blockX, entity.blockZ)
        return entity.blockY >= surface - BELOW_THE_SURFACE
    }

    /** The surface as it stands: while a chunk is generated, the heightmap generation keeps. */
    private fun surfaceOf(level: ServerLevelAccessor): Heightmap.Types =
        if (level is WorldGenRegion) Heightmap.Types.WORLD_SURFACE_WG else Heightmap.Types.WORLD_SURFACE

    private const val NONE_OF_IT = 0.0

    /** How far below the surface a creature has to be to be kept from an ended Age's: a cellar, a mine. */
    private const val BELOW_THE_SURFACE = 12
}

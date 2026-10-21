package co.voik.agesandtheart.generation

import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.LinkAnchor
import co.voik.agesandtheart.age.aspect.Aspect
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.HolderSet
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.attribute.EnvironmentAttributes
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.MobSpawnSettings
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.structure.Structure

/**
 * Where `link in <anchor>` arrives: the anchor nearest the origin within [REACH], found off the generator, never off
 * generated ground — a biome, a structure, the sea, or a biome where a feature or a creature can appear.
 */
object LinkingIn {

    /** The column nearest the origin holding [anchor], or null where none is within [REACH] or it cannot be looked for. */
    fun nearest(level: ServerLevel, recipe: AgeRecipe, anchor: LinkAnchor): BlockPos? = when (anchor.aspect) {
        Aspect.TERRAIN -> nearestTerritory(recipe, anchor.members)
        Aspect.BIOMES -> nearestBiome(level) { biome -> anchor.isAnyOf(biome) }
        Aspect.FEATURES -> nearestBiome(level) { biome -> growsAnyOf(level, biome, anchor.members) }
        Aspect.SPAWNS -> nearestBiome(level) { biome -> spawnsAnyOf(biome, anchor.members) }
        Aspect.STRUCTURES -> nearestStructure(level, anchor.members)
        Aspect.SEA, Aspect.WATERS -> nearestSea(level)
        else -> null
    }

    private fun LinkAnchor.isAnyOf(biome: Holder<Biome>): Boolean =
        members.isEmpty() || biome.unwrapKey().map { it.identifier().toString() in members }.orElse(false)

    private fun nearestBiome(level: ServerLevel, wanted: (Holder<Biome>) -> Boolean): BlockPos? =
        level.chunkSource.generator.biomeSource.findClosestBiome3d(
            BlockPos(0, level.seaLevel, 0), REACH, BIOME_STEP, BIOME_STEP_UP, wanted, level.chunkSource.randomState(), level,
        )?.first

    /** Whether the Age grows any of [features] in [biome], as its own generator would place them. */
    private fun growsAnyOf(level: ServerLevel, biome: Holder<Biome>, features: Set<String>): Boolean =
        level.chunkSource.generator.getBiomeGenerationSettings(biome).features().any { step ->
            step.any { placed -> placed.unwrapKey().map { it.identifier().toString() in features }.orElse(false) }
        }

    /** Whether [biome] offers any of [creatures] in any spawn pass. */
    private fun spawnsAnyOf(biome: Holder<Biome>, creatures: Set<String>): Boolean {
        val spawns = biome.value().attributes.applyModifier(EnvironmentAttributes.NATURAL_MOB_SPAWNS, MobSpawnSettings.EMPTY)
        return MobCategory.entries.any { category ->
            spawns.getMobsToSpawn(category).unwrap().any { BuiltInRegistries.ENTITY_TYPE.getKey(it.value().type()).toString() in creatures }
        }
    }

    private fun nearestStructure(level: ServerLevel, sets: Set<String>): BlockPos? {
        val lookup = level.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET)
        val structures: List<Holder<Structure>> = sets.mapNotNull(Identifier::tryParse)
            .mapNotNull { lookup.get(net.minecraft.resources.ResourceKey.create(Registries.STRUCTURE_SET, it)).orElse(null) }
            .flatMap { set -> set.value().structures().map { it.structure() } }
        if (structures.isEmpty()) return null
        return level.chunkSource.generator.findNearestMapStructure(
            level, HolderSet.direct(structures), BlockPos.ZERO, SectionPos.blockToSectionCoord(REACH), false,
        )?.first
    }

    /**
     * The nearest column of a territory whose landform is one of [landforms], read off the region map alone — a
     * landform that takes the whole Age is everywhere, so the origin's own column.
     */
    private fun nearestTerritory(recipe: AgeRecipe, landforms: Set<String>): BlockPos? {
        val composition = recipe.composition ?: return null
        val wanted = composition.terrains.indices.filter { composition.terrains[it].key in landforms }.toSet()
        if (wanted.isEmpty()) return null
        if (composition.terrains.size == 1) return BlockPos.ZERO
        val map = recipe.character.mapFor(Aspect.TERRAIN, composition.spreadOf(Aspect.TERRAIN), recipe.seed)
        return outward(TERRITORY_STEP) { x, z -> map.memberAt(x, z) in wanted }
    }

    /** The first column [holds] that a walk outward from the origin a ring of [step] at a time reaches, within [REACH]. */
    private fun outward(step: Int, holds: (Int, Int) -> Boolean): BlockPos? {
        for (ring in 0..REACH / step) {
            val reach = ring * step
            for (along in -reach..reach step step.coerceAtLeast(1)) {
                val onTheRing = listOf(along to -reach, along to reach, -reach to along, reach to along)
                onTheRing.firstOrNull { (x, z) -> holds(x, z) }?.let { (x, z) -> return BlockPos(x, 0, z) }
            }
        }
        return null
    }

    /** The nearest column whose surface is the sea, walked outward a ring at a time. */
    private fun nearestSea(level: ServerLevel): BlockPos? {
        val generator = level.chunkSource.generator
        val randomState = level.chunkSource.randomState()
        fun isSea(x: Int, z: Int): Boolean {
            val surface = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState)
            val floor = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState)
            return surface > floor
        }
        return outward(SEA_STEP, ::isSea)
    }

    /** How far from the origin a link-in looks (Jonah, 2026-10-21). */
    const val REACH = 2000

    /** Vanilla's own `/locate biome` steps. */
    private const val BIOME_STEP = 32
    private const val BIOME_STEP_UP = 64

    /** A sea is wide; a step finer than this costs columns of the generator and finds the same water. */
    private const val SEA_STEP = 32

    /** Far below the smallest territory a share produces, as `/age locate` steps. */
    private const val TERRITORY_STEP = 64
}

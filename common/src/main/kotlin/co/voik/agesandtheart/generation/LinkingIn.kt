package co.voik.agesandtheart.generation

import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.LinkAnchor
import co.voik.agesandtheart.age.aspect.Aspect
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.HolderSet
import net.minecraft.core.QuartPos
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
 * generated ground — a biome, a structure, the sea, or a biome where a feature or a creature can appear — and the
 * spot to stand there: on the surface, or in the open below it where what was named lies underground.
 */
object LinkingIn {

    /** Where a link arrives for [anchor], the block the visitor's feet are in, or null where none is within [REACH]. */
    fun arrivalAt(level: ServerLevel, recipe: AgeRecipe, anchor: LinkAnchor): BlockPos? {
        fun onTheSurface(found: BlockPos?) = found?.let { Ages.footingIn(level, it.x, it.z) }
        return when (anchor.aspect) {
            Aspect.TERRAIN -> onTheSurface(nearestTerritory(recipe, anchor.members))
            Aspect.BIOMES -> inBiome(level) { biome -> anchor.isAnyOf(biome) }
            Aspect.FEATURES -> inBiome(level) { biome -> growsAnyOf(level, biome, anchor.members) }
            Aspect.SPAWNS -> inBiome(level) { biome -> spawnsAnyOf(biome, anchor.members) }
            Aspect.STRUCTURES -> onTheSurface(nearestStructure(level, anchor.members))
            Aspect.SEA, Aspect.WATERS -> onTheSurface(nearestSea(level))
            else -> null
        }
    }

    /**
     * Into the nearest biome [wanted] holds to [from], within [reach]: on the surface where the surface is it, and
     * otherwise in the open below, near the height it was found at — a cave biome is under ground some other biome
     * covers.
     */
    internal fun inBiome(
        level: ServerLevel,
        from: BlockPos = BlockPos(0, level.seaLevel, 0),
        reach: Int = REACH,
        wanted: (Holder<Biome>) -> Boolean,
    ): BlockPos? {
        val found = nearestBiome(level, from, reach, wanted) ?: return null
        val surface = Ages.footingIn(level, found.x, found.z)
        val atTheSurface = level.chunkSource.generator.biomeSource.createUncachedResolver(level.chunkSource.randomState())
            .getNoiseBiome(QuartPos.fromBlock(found.x), QuartPos.fromBlock(surface.y), QuartPos.fromBlock(found.z))
        return if (wanted(atTheSurface)) surface else openBelow(level, found) ?: surface
    }

    /**
     * Standing room under ground near [found]: air with headroom over something that holds a visitor up, in its
     * column or one beside it, nearest the height it was found at first. Null where the rock is solid round it.
     */
    private fun openBelow(level: ServerLevel, found: BlockPos): BlockPos? {
        val generator = level.chunkSource.generator
        val randomState = level.chunkSource.randomState()
        val heights = (0..CAVE_REACH).flatMap { away -> listOf(found.y - away, found.y + away) }.distinct()
            .filter { it > level.minY && it + 1 < level.maxY }
        for (dx in -BESIDE..BESIDE step BESIDE_STEP) for (dz in -BESIDE..BESIDE step BESIDE_STEP) {
            val column = generator.getBaseColumn(found.x + dx, found.z + dz, level, randomState)
            fun isOpen(y: Int) = column.getBlock(y).isAir
            @Suppress("DEPRECATION") // `isSolid` is what a mob stands on, and is the reading `ScarabColony` makes too.
            fun holdsYouUp(y: Int) = column.getBlock(y).isSolid
            val standing = heights.firstOrNull { y -> isOpen(y) && isOpen(y + 1) && holdsYouUp(y - 1) }
            if (standing != null) return BlockPos(found.x + dx, standing, found.z + dz)
        }
        return null
    }

    private fun LinkAnchor.isAnyOf(biome: Holder<Biome>): Boolean =
        members.isEmpty() || biome.unwrapKey().map { it.identifier().toString() in members }.orElse(false)

    private fun nearestBiome(level: ServerLevel, from: BlockPos, reach: Int, wanted: (Holder<Biome>) -> Boolean): BlockPos? =
        level.chunkSource.generator.biomeSource.findClosestBiome3d(
            from, reach, BIOME_STEP, BIOME_STEP_UP, wanted, level.chunkSource.randomState(), level,
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

    /** How far above and below the height a biome was found at its standing room is looked for. */
    private const val CAVE_REACH = 24

    /** How far beside the found column, and in what steps, the columns looked through for room reach. */
    private const val BESIDE = 8
    private const val BESIDE_STEP = 4

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

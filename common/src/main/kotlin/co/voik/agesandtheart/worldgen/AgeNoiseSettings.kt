package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.SeaFill
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.TerrainFill
import net.minecraft.core.Holder
import net.minecraft.core.QuartPos
import net.minecraft.world.level.levelgen.DensityFunction
import net.minecraft.world.level.levelgen.DensityFunctions
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings
import net.minecraft.world.level.levelgen.NoiseRouter
import net.minecraft.world.level.levelgen.NoiseSettings
import net.minecraft.world.level.levelgen.SurfaceRules

/**
 * Our vertical layout in the shape vanilla's machinery expects — and, for a
 * [co.voik.agesandtheart.generation.AgeChunkGenerator] being a `NoiseBasedChunkGenerator`, the settings the
 * game reads to build our `RandomState`.
 *
 * That is what the subclass buys: `ChunkMap` branches on `instanceof NoiseBasedChunkGenerator` to decide
 * whether to build a `RandomState` from the generator's own settings or from `NoiseGeneratorSettings.dummy()`,
 * so a peer can never be handed a real climate sampler.
 *
 * **Here rather than in the generator's companion**, where they were forced to live because a superclass
 * constructor call cannot see the instance being built. They are pure functions of a recipe's rock, and
 * nothing about them needs a generator to exist.
 */
internal fun settingsFor(
    seaFill: SeaFill,
    surfaceRule: SurfaceRules.RuleSource,
    climate: Holder<NoiseGeneratorSettings>?,
    fill: TerrainFill,
    window: VerticalWindow,
    field: TerrainField,
    uncut: TerrainField?,
) = NoiseGeneratorSettings(
    NoiseSettings.create(window.minY, window.height, NOISE_CELLS_HORIZONTAL, NOISE_CELLS_VERTICAL),
    // The Age's own material, not a constant, which is what makes a surface rule fire over it:
    // `SurfaceSystem` recognises rock by comparing against these settings' default block, so
    // laying blackstone while declaring stone paints no surface at all. One block for the whole
    // Age, so several materials are recognised over [TerrainFill.representative] only.
    fill.representative,
    seaFill.representative,
    routerFor(
        climate,
        PreliminarySurface(field, uncut, window.minY, window.topY - 1, QuartPos.toBlock(NOISE_CELLS_VERTICAL)),
    ),
    surfaceRule,
    emptyList(),
    // Coerced, because VOID's level is a sentinel rather than a height and this one is read as a
    // height by the superclass, by features and by the surface system.
    seaFill.level.coerceAtLeast(window.minY),
    /* disableMobGeneration = */ true,
    /* aquifersEnabled = */ false,
    /* oreVeinsEnabled = */ false,
    /* useLegacyRandomSource = */ false,
)

/**
 * **The climate half of a named router, and nothing else.** `ChunkMap` builds the level's `RandomState`
 * from these settings, so this is what every consumer is handed — `applyCarvers`, the inherited
 * `createBiomes`, `/age biomes`.
 *
 * **The terrain half stays zero**, since our shape is the field tree's and any density read here
 * would describe a world that does not exist. **`depth` stays zero too**, which only looks
 * inconsistent: depth is ours ([co.voik.agesandtheart.worldgen.biome.ClimateDepth]), and vanilla's
 * own depth function describes vanilla's relief — a terrain function wearing a climate name.
 *
 * **One exception, and it earns itself: `preliminarySurfaceLevel`.** `NoiseChunk` floors that slot
 * into a column's preliminary surface, which is how deep the surface system's frozen-ocean icebergs
 * reach and what vanilla's `abovePreliminarySurface` compares against. [PreliminarySurface] answers it
 * from the field tree — as a height, not a density, since a height is what the slot holds.
 */
private fun routerFor(climate: Holder<NoiseGeneratorSettings>?, surface: DensityFunction): NoiseRouter {
    val vanilla = climate?.value()?.noiseRouter() ?: return inertRouterOver(surface)
    val nothing = DensityFunctions.zero()
    return NoiseRouter(
        nothing, nothing, nothing, nothing,
        vanilla.temperature(), vanilla.vegetation(), vanilla.continents(), vanilla.erosion(),
        /* depth = */ nothing,
        vanilla.ridges(),
        /* preliminarySurfaceLevel = */ surface,
        nothing, nothing, nothing, nothing,
    )
}

/** A router describing nothing but where the rock stands — what an Age with no climate gets. */
private fun inertRouterOver(surface: DensityFunction): NoiseRouter = DensityFunctions.zero().let { nothing ->
    NoiseRouter(
        nothing, nothing, nothing, nothing, nothing,
        nothing, nothing, nothing, nothing, nothing,
        surface, nothing, nothing, nothing, nothing,
    )
}

// Cell sizes for the layout description handed to vanilla's machinery; they match the
// overworld's, which is the shape all of it is tuned around.
private const val NOISE_CELLS_HORIZONTAL = 1
private const val NOISE_CELLS_VERTICAL = 2

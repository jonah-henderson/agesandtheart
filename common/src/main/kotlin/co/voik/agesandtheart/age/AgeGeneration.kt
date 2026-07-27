package co.voik.agesandtheart.age

import co.voik.agesandtheart.location
import co.voik.agesandtheart.worldgen.CavernField
import co.voik.agesandtheart.worldgen.ErodedField
import co.voik.agesandtheart.worldgen.NoiseField
import co.voik.agesandtheart.worldgen.PillarField
import co.voik.agesandtheart.worldgen.PyramidField
import co.voik.agesandtheart.worldgen.ShapesField
import co.voik.agesandtheart.worldgen.VanillaDelegate
import co.voik.agesandtheart.worldgen.SpireChunkGenerator
import co.voik.agesandtheart.worldgen.SpireField
import co.voik.agesandtheart.worldgen.biome.AgeBiomeSource
import net.minecraft.core.HolderSet
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.biome.FixedBiomeSource
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.GenerationStep
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets
import net.minecraft.world.level.levelgen.structure.StructureSet

/**
 * Turns an [AgeRecipe] into the generator that builds its world.
 *
 * This is the one place a recipe becomes machinery, and it is deliberately a *pure function of the
 * recipe* (plus the server, for the registries the presets need): an Age is replayable data, so the
 * same recipe must give the same world on every open, restart-replay included.
 *
 * Each preset is presently a whole world. Phase 2 of the Art splits them by slot, at which point this
 * function assembles a generator from several rather than choosing one — the `when` below is the seam
 * that grows into that.
 */
object AgeGeneration {
    /**
     * The Spire dimension type (registered as a datapack dimension-type at load). Its `effects` id is
     * `agesandtheart:age`, the marker the client watches to attach the custom Spire sky renderer.
     */
    val AGE_DIMENSION_TYPE: ResourceLocation = "age".location()

    /**
     * The plain dimension type — identical layout, but vanilla (`minecraft:overworld`) effects, so it
     * gets the normal sky. Used by the field-generator Ages while we iterate on terrain (the Spire
     * clouds obscure it). Per-Age skies become symbol-driven later, like the generator itself.
     */
    val AGE_PLAIN_DIMENSION_TYPE: ResourceLocation = "age_plain".location()

    /** The custom biome (green plasma water), registered as a datapack biome at load. */
    val PLASMA_BIOME: ResourceLocation = "plasma".location()

    fun chunkGenerator(server: MinecraftServer, recipe: AgeRecipe): ChunkGenerator = when (recipe.preset) {
        // The Tier-B delegates wrap vanilla's whole pipeline, biome source and all.
        AgePreset.VANILLA -> VanillaDelegate.overworld(server)
        AgePreset.VANILLA_BARE -> VanillaDelegate.bareOverworld(server)
        // Vanilla's climate over vanilla's biome table — the sane default for an Age whose author has
        // expressed no preference. See [AgeBiomeSource] for why vanilla's own MultiNoiseBiomeSource
        // cannot work for a generator like ours.
        AgePreset.HILLS -> NoiseField.hillsGenerator(
            AgeBiomeSource.vanillaOverworld(server, recipe.seed),
            undergroundCarvers(server),
            overworldStructures(server),
        )
        // No vanilla carvers: this Age's caves are its field tree, and the point is to see what
        // ridged noise alone makes of the rock without cave-and-canyon walks confusing the picture.
        AgePreset.CAVERNS -> CavernField.generator(AgeBiomeSource.vanillaOverworld(server, recipe.seed))
        // Spire — bespoke preset and field rebuild alike — wears its own green plasma biome, since
        // that world is what the whole look is being designed for.
        AgePreset.SPIRE -> SpireChunkGenerator(plasmaBiome(server), recipe.seed)
        AgePreset.FIELD -> SpireField.generator(plasmaBiome(server), erosionCarvers(server))
        // The shape samplers stay on vanilla the_void: a bright, neutral sky with no features or
        // structures, which suits iterating on form with nothing in the way.
        AgePreset.PYRAMIDS -> PyramidField.generator(voidBiome(server))
        AgePreset.PYRINGS -> PyramidField.ringsGenerator(voidBiome(server))
        AgePreset.PYRVARIED -> PyramidField.variedGenerator(voidBiome(server))
        AgePreset.SHAPES -> ShapesField.generator(voidBiome(server))
        AgePreset.PILLARS -> PillarField.generator(voidBiome(server))
        AgePreset.ERODED -> ErodedField.generator(voidBiome(server))
    }

    /** Spire's own green plasma sea. */
    private fun plasmaBiome(server: MinecraftServer) =
        fixedBiome(server, ResourceKey.create(Registries.BIOME, PLASMA_BIOME))

    /** Vanilla's barren `the_void`: a bright, neutral sky and nothing growing in the way of the shape. */
    private fun voidBiome(server: MinecraftServer) = fixedBiome(server, Biomes.THE_VOID)

    /** One biome everywhere — for the Ages whose look is the shape itself. */
    private fun fixedBiome(server: MinecraftServer, biome: ResourceKey<Biome>) =
        FixedBiomeSource(server.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(biome))

    /**
     * Vanilla's cave and canyon carvers, resolved from the registry so an Age can name them in its own
     * recipe. Carvers normally ride on a biome, but field Ages sit on the barren `the_void`, which
     * carries none — and an Age already describes its whole world as replayable data, so its carvers
     * belong there too. Keys are built by hand rather than taken from `net.minecraft.data.worldgen`,
     * which is datagen territory.
     */
    private fun undergroundCarvers(server: MinecraftServer): Map<GenerationStep.Carving, HolderSet<ConfiguredWorldCarver<*>>> {
        val configured = server.registryAccess().lookupOrThrow(Registries.CONFIGURED_CARVER)
        val caves = HolderSet.direct(
            configured.getOrThrow(vanillaCarver("cave")),
            configured.getOrThrow(vanillaCarver("cave_extra_underground")),
            configured.getOrThrow(vanillaCarver("canyon")),
        )
        return mapOf(GenerationStep.Carving.AIR to caves)
    }

    /** Our own erosion pass — the thing that pares Spire's blocky masses back to ribs and spires. */
    private fun erosionCarvers(server: MinecraftServer): Map<GenerationStep.Carving, HolderSet<ConfiguredWorldCarver<*>>> {
        val configured = server.registryAccess().lookupOrThrow(Registries.CONFIGURED_CARVER)
        val erosion = ResourceKey.create(Registries.CONFIGURED_CARVER, "erosion".location())
        return mapOf(GenerationStep.Carving.AIR to HolderSet.direct(configured.getOrThrow(erosion)))
    }

    private fun vanillaCarver(name: String): ResourceKey<ConfiguredWorldCarver<*>> =
        ResourceKey.create(Registries.CONFIGURED_CARVER, ResourceLocation.withDefaultNamespace(name))

    /**
     * Everything vanilla builds on an overworld, named by the Age rather than inherited — the same rule
     * carvers already follow, and for the same reason: an Age is replayable data, so what stands in it
     * belongs in its recipe.
     *
     * The list is every set in [BuiltinStructureSets] bar the three that belong to the other dimensions.
     * Naming the rest is not the same as placing them: the generator's state builder drops any set whose
     * structures want a biome this Age's biome source cannot produce, so an Age with no jungle gets no
     * jungle temples without anyone having to say so. Leaving the nether and end sets out is honesty
     * rather than filtering — no overworld biome could ever admit them.
     */
    private fun overworldStructures(server: MinecraftServer): HolderSet<StructureSet> {
        val sets = server.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET)
        return HolderSet.direct(OVERWORLD_STRUCTURE_SETS.map(sets::getOrThrow))
    }

    private val OVERWORLD_STRUCTURE_SETS = listOf(
        BuiltinStructureSets.VILLAGES,
        BuiltinStructureSets.DESERT_PYRAMIDS,
        BuiltinStructureSets.IGLOOS,
        BuiltinStructureSets.JUNGLE_TEMPLES,
        BuiltinStructureSets.SWAMP_HUTS,
        BuiltinStructureSets.PILLAGER_OUTPOSTS,
        BuiltinStructureSets.OCEAN_MONUMENTS,
        BuiltinStructureSets.WOODLAND_MANSIONS,
        BuiltinStructureSets.BURIED_TREASURES,
        BuiltinStructureSets.MINESHAFTS,
        BuiltinStructureSets.RUINED_PORTALS,
        BuiltinStructureSets.SHIPWRECKS,
        BuiltinStructureSets.OCEAN_RUINS,
        BuiltinStructureSets.ANCIENT_CITIES,
        BuiltinStructureSets.STRONGHOLDS,
        BuiltinStructureSets.TRAIL_RUINS,
        BuiltinStructureSets.TRIAL_CHAMBERS,
    )

    /**
     * The dimension type (and thus sky) an Age wears — the Spire worlds keep the custom Age sky.
     *
     * Derived from the preset for now. Sky is a slot of its own in the design (§3.1), so this becomes
     * something the recipe *names* rather than something inferred from its landform.
     */
    fun dimensionType(recipe: AgeRecipe): ResourceLocation =
        if (recipe.preset in SPIRE_PRESETS) AGE_DIMENSION_TYPE else AGE_PLAIN_DIMENSION_TYPE

    /** The two Spire worlds: the original bespoke preset, and its rebuild as a field tree. */
    private val SPIRE_PRESETS = setOf(AgePreset.SPIRE, AgePreset.FIELD)
}

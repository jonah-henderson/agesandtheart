package co.voik.agesandtheart.age

import co.voik.agesandtheart.location
import co.voik.agesandtheart.worldgen.NoiseField
import co.voik.agesandtheart.worldgen.PillarField
import co.voik.agesandtheart.worldgen.PyramidField
import co.voik.agesandtheart.worldgen.ShapesField
import co.voik.agesandtheart.worldgen.VanillaDelegate
import co.voik.agesandtheart.worldgen.SpireChunkGenerator
import co.voik.agesandtheart.worldgen.SpireField
import net.minecraft.core.HolderSet
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.biome.FixedBiomeSource
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.GenerationStep
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver

/**
 * Builds the generation recipe for an Age.
 *
 * An Age's generator is chosen by a persisted generator-kind key (see [AgeSavedData]) so the same
 * kind is rebuilt on every open, including restart-replay. Two kinds exist today, both on the custom
 * `plasma` biome: [GENERATOR_SPIRE] (the bespoke [SpireChunkGenerator] preset — the floating-island
 * "Spire" world) and [GENERATOR_FIELD] (the composable [co.voik.agesandtheart.worldgen.FieldChunkGenerator],
 * currently driving the [SpireField] island preset — our first field-toolkit world). v1 makes the
 * choice symbol-driven; the key mechanism is the seam that grows into that.
 */
object AgeGeneration {
    /** Persisted generator-kind keys (stored per Age in [AgeSavedData], selected at open time). */
    const val GENERATOR_SPIRE = "spire"
    const val GENERATOR_FIELD = "field"
    const val GENERATOR_PYRAMIDS = "pyramids"
    const val GENERATOR_PYRINGS = "pyrings"
    const val GENERATOR_PYRVARIED = "pyrvaried"
    const val GENERATOR_HILLS = "hills"
    const val GENERATOR_SHAPES = "shapes"
    const val GENERATOR_PILLARS = "pillars"

    /** Tier-B vanilla delegates — real Minecraft generation, and our benchmark reference points. */
    const val GENERATOR_VANILLA = "vanilla"
    const val GENERATOR_VANILLA_BARE = "vanillabare"
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

    fun chunkGenerator(server: MinecraftServer, id: ResourceLocation, seed: Long): ChunkGenerator {
        val generatorKey = AgeSavedData.get(server).generatorKey(id)
        // Tier-B delegates bring their own biome source, so they answer before the fixed one is built.
        when (generatorKey) {
            GENERATOR_VANILLA -> return VanillaDelegate.overworld(server)
            GENERATOR_VANILLA_BARE -> return VanillaDelegate.bareOverworld(server)
        }
        // Spire uses the moody green plasma biome; field Ages use vanilla the_void — a bright, normal
        // sky with no features/structures/mobs. (Plains pulled in village structures, which crash on
        // our flat terrain with "Bound must be positive"; revisit when we enable real decoration.)
        // Spire — bespoke preset and field rebuild alike — wears its own green plasma biome, since that
        // world is what the whole look is being designed for. The other field Ages stay on vanilla
        // the_void: a bright, neutral sky with no features or structures, which suits iterating on shape.
        val biomeKey = if (generatorKey in SPIRE_KINDS) {
            ResourceKey.create(Registries.BIOME, PLASMA_BIOME)
        } else {
            Biomes.THE_VOID
        }
        val biome = server.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(biomeKey)
        val biomes = FixedBiomeSource(biome)
        return when (generatorKey) {
            GENERATOR_FIELD -> SpireField.generator(biomes, erosionCarvers(server))
            GENERATOR_PYRAMIDS -> PyramidField.generator(biomes)
            GENERATOR_PYRINGS -> PyramidField.ringsGenerator(biomes)
            GENERATOR_PYRVARIED -> PyramidField.variedGenerator(biomes)
            GENERATOR_HILLS -> NoiseField.hillsGenerator(biomes, undergroundCarvers(server))
            GENERATOR_SHAPES -> ShapesField.generator(biomes)
            GENERATOR_PILLARS -> PillarField.generator(biomes)
            else -> SpireChunkGenerator(biomes, seed)
        }
    }

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

    /** The dimension type (and thus sky) for an Age — the Spire worlds keep the custom Age sky. */
    fun dimensionType(server: MinecraftServer, id: ResourceLocation): ResourceLocation =
        if (AgeSavedData.get(server).generatorKey(id) in SPIRE_KINDS) {
            AGE_DIMENSION_TYPE
        } else {
            AGE_PLAIN_DIMENSION_TYPE
        }

    /** The two Spire worlds: the original bespoke preset, and its rebuild as a field tree. */
    private val SPIRE_KINDS = setOf(GENERATOR_SPIRE, GENERATOR_FIELD)
}

package co.voik.agesandtheart.age

import co.voik.agesandtheart.location
import co.voik.agesandtheart.worldgen.NoiseField
import co.voik.agesandtheart.worldgen.PyramidField
import co.voik.agesandtheart.worldgen.SpireChunkGenerator
import co.voik.agesandtheart.worldgen.SpireField
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.biome.FixedBiomeSource
import net.minecraft.world.level.chunk.ChunkGenerator

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
        // Spire uses the moody green plasma biome; field Ages use vanilla the_void — a bright, normal
        // sky with no features/structures/mobs. (Plains pulled in village structures, which crash on
        // our flat terrain with "Bound must be positive"; revisit when we enable real decoration.)
        val biomeKey = if (generatorKey == GENERATOR_SPIRE) {
            ResourceKey.create(Registries.BIOME, PLASMA_BIOME)
        } else {
            Biomes.THE_VOID
        }
        val biome = server.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(biomeKey)
        val biomes = FixedBiomeSource(biome)
        return when (generatorKey) {
            GENERATOR_FIELD -> SpireField.generator(biomes)
            GENERATOR_PYRAMIDS -> PyramidField.generator(biomes)
            GENERATOR_PYRINGS -> PyramidField.ringsGenerator(biomes)
            GENERATOR_PYRVARIED -> PyramidField.variedGenerator(biomes)
            GENERATOR_HILLS -> NoiseField.hillsGenerator(biomes)
            else -> SpireChunkGenerator(biomes, seed)
        }
    }

    /** The dimension type (and thus sky) for an Age — only the Spire preset keeps the custom Age sky. */
    fun dimensionType(server: MinecraftServer, id: ResourceLocation): ResourceLocation =
        when (AgeSavedData.get(server).generatorKey(id)) {
            GENERATOR_SPIRE -> AGE_DIMENSION_TYPE
            else -> AGE_PLAIN_DIMENSION_TYPE
        }
}

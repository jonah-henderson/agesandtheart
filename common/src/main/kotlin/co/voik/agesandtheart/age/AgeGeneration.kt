package co.voik.agesandtheart.age

import co.voik.agesandtheart.location
import co.voik.agesandtheart.worldgen.SpireChunkGenerator
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.biome.FixedBiomeSource
import net.minecraft.world.level.chunk.ChunkGenerator

/**
 * Builds the generation recipe for an Age.
 *
 * Currently every Age is the "Spire" world — floating islands over a plasma sea (see
 * [SpireChunkGenerator]) on our custom `plasma` biome. Loader-agnostic vanilla code, reused by
 * whichever platform backend creates the dimension. v1 makes the choice symbol-driven, and there
 * will be several generators.
 */
object AgeGeneration {
    /**
     * The custom dimension type every Age uses (registered as a datapack dimension-type at load).
     * Its `effects` id is this same location, which the client uses as a marker to attach the
     * custom Age sky renderer.
     */
    val AGE_DIMENSION_TYPE: ResourceLocation = "age".location()

    /** The custom biome (green plasma water), registered as a datapack biome at load. */
    val PLASMA_BIOME: ResourceLocation = "plasma".location()

    fun chunkGenerator(server: MinecraftServer, seed: Long): ChunkGenerator {
        val plasma = server.registryAccess()
            .lookupOrThrow(Registries.BIOME)
            .getOrThrow(ResourceKey.create(Registries.BIOME, PLASMA_BIOME))
        return SpireChunkGenerator(FixedBiomeSource(plasma), seed)
    }
}

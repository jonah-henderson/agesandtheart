package co.voik.agesandtheart.age

import co.voik.agesandtheart.location
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.FlatLevelSource
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings

/**
 * Builds the generation recipe for a spike-era Age.
 *
 * For now every Age is a superflat world (built only from the server's registries, so it's
 * self-contained) — visually distinct from the overworld, which is enough to prove you travelled
 * somewhere new. v1 replaces this with real symbol-driven generation; the shape (`server ->
 * generator`) stays the same. Loader-agnostic vanilla code, reused by whichever platform backend
 * creates the dimension.
 */
object AgeGeneration {
    /**
     * The custom dimension type every Age uses (registered as a datapack dimension-type at load).
     * Its `effects` id is this same location, which the client uses as a marker to attach the
     * custom Age sky renderer.
     */
    val AGE_DIMENSION_TYPE: ResourceLocation = "age".location()

    fun chunkGenerator(server: MinecraftServer): ChunkGenerator {
        val registries = server.registryAccess()
        return FlatLevelSource(
            FlatLevelGeneratorSettings.getDefault(
                registries.lookupOrThrow(Registries.BIOME),
                registries.lookupOrThrow(Registries.STRUCTURE_SET),
                registries.lookupOrThrow(Registries.PLACED_FEATURE),
            ),
        )
    }
}

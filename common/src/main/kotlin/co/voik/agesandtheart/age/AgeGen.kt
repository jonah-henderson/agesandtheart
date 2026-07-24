package co.voik.agesandtheart.age

import co.voik.agesandtheart.location
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.FlatLevelSource
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings

/**
 * Builds the generation "recipe" for a spike-era Age.
 *
 * For now every Age is a superflat world (built only from the server's registries, so it's
 * self-contained) — visually distinct from the overworld, which is enough to prove you
 * travelled somewhere new. v1 replaces this with real symbol-driven generation; the shape
 * (server -> generator) stays the same. This is loader-agnostic vanilla code, so it lives in
 * `common` and is reused by whichever platform backend creates the dimension.
 */
object AgeGen {
    /**
     * The custom dimension type every Age uses (registered as a datapack dimension-type at load).
     * Its `effects` id is this same location, which the client uses as a marker to attach the
     * custom Age sky renderer.
     */
    val AGE_DIMENSION_TYPE: ResourceLocation = "age".location()

    fun chunkGenerator(server: MinecraftServer): ChunkGenerator {
        val access = server.registryAccess()
        return FlatLevelSource(
            FlatLevelGeneratorSettings.getDefault(
                access.lookupOrThrow(Registries.BIOME),
                access.lookupOrThrow(Registries.STRUCTURE_SET),
                access.lookupOrThrow(Registries.PLACED_FEATURE),
            ),
        )
    }
}

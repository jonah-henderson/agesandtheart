package co.voik.agesandtheart.age

import net.minecraft.core.registries.Registries
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

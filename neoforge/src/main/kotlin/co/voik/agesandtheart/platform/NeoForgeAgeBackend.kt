package co.voik.agesandtheart.platform

import co.voik.agesandtheart.age.AgeGeneration
import co.voik.agesandtheart.age.AgeSavedData
import co.voik.agesandtheart.platform.services.AgeBackend
import co.voik.ephemeris.RuntimeLevelConfig
import co.voik.ephemeris.RuntimeLevels
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel

/**
 * NeoForge's runtime-dimension backend, on Ephemeris.
 *
 * Identical to Fabric's but for the class name, which is the point: the two loaders run the same code and
 * differ only in how the loader is told a level arrived — see `notes/neoforge-dimensions-research.md`.
 */
class NeoForgeAgeBackend : AgeBackend {
    override val isSupported: Boolean = true

    override fun openAge(server: MinecraftServer, id: Identifier): ServerLevel {
        val recipe = AgeSavedData.get(server).recipe(id)
        return RuntimeLevels.open(
            server,
            id,
            RuntimeLevelConfig(
                dimensionType = server.registryAccess()
                    .lookupOrThrow(Registries.DIMENSION_TYPE)
                    .getOrThrow(ResourceKey.create(Registries.DIMENSION_TYPE, AgeGeneration.dimensionType(recipe))),
                generator = AgeGeneration.chunkGenerator(server, recipe),
                seed = recipe.seed,
                customSpawners = AgeGeneration.spawnersFor(server, recipe),
            ),
        )
    }

    override fun deleteAge(server: MinecraftServer, id: Identifier): Boolean =
        RuntimeLevels.delete(server, id)
}

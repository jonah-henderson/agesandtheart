package co.voik.agesandtheart.platform

import co.voik.agesandtheart.age.AgeGeneration
import co.voik.agesandtheart.age.AgeSavedData
import co.voik.agesandtheart.platform.services.AgeBackend
import co.voik.runtimelevels.RuntimeLevelConfig
import co.voik.runtimelevels.RuntimeLevels
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel

/**
 * Fabric's runtime-dimension backend, on `co.voik.runtimelevels` rather than on Fantasy.
 *
 * Identical to NeoForge's but for the class name, which is the point of the exercise.
 */
class FabricAgeBackend : AgeBackend {
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
            ),
        )
    }

    override fun deleteAge(server: MinecraftServer, id: Identifier): Boolean = false
}

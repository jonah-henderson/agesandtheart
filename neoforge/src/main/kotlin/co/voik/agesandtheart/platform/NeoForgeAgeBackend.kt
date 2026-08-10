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
 * NeoForge's runtime-dimension backend, on `co.voik.runtimelevels` rather than on Fantasy.
 *
 * Fantasy is Fabric-only, which is why this was a stub for so long. The library it is replaced by is ours
 * and lives in `common`, so the two loaders now run the same code with only the level-arrived notification
 * differing — see `notes/neoforge-dimensions-research.md` for why that is the only genuine difference.
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
            ),
        )
    }

    /** Not yet — see the spike's note. Deleting a live level is the last piece and the fiddliest. */
    override fun deleteAge(server: MinecraftServer, id: Identifier): Boolean = false
}

package co.voik.agesandtheart.platform

import co.voik.agesandtheart.age.AgeGeneration
import co.voik.agesandtheart.age.AgeSavedData
import co.voik.agesandtheart.platform.services.AgeBackend
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import xyz.nucleoid.fantasy.Fantasy
import xyz.nucleoid.fantasy.RuntimeLevelConfig

/**
 * Fabric runtime-dimension backend, implemented with Fantasy (NucleoidMC). Fantasy's persistent levels
 * save to disk but are **not auto-restored on restart** — [openAge] is get-or-create, so calling it with a
 * known id after a restart re-attaches the saved chunks.
 */
class FabricAgeBackend : AgeBackend {
    override val isSupported: Boolean = true

    override fun openAge(server: MinecraftServer, id: Identifier): ServerLevel? =
        Fantasy.get(server).getOrOpenPersistentLevel(id, configFor(server, id)).asLevel()

    /**
     * Fantasy hands out deletion through the level's *handle*, and the only route to a handle is
     * get-or-open — so an Age that is not currently loaded is briefly opened in order to be discarded.
     * Harmless, and it keeps deletion working after a restart, when nothing has been opened yet.
     */
    override fun deleteAge(server: MinecraftServer, id: Identifier): Boolean {
        Fantasy.get(server).getOrOpenPersistentLevel(id, configFor(server, id)).delete()
        return true
    }

    /**
     * Fantasy's description of an Age's level, built from the recipe the Age was written from — the
     * one place a recipe becomes a live dimension.
     */
    private fun configFor(server: MinecraftServer, id: Identifier): RuntimeLevelConfig {
        val recipe = AgeSavedData.get(server).recipe(id)
        return RuntimeLevelConfig()
            .setDimensionType(ResourceKey.create(Registries.DIMENSION_TYPE, AgeGeneration.dimensionType(recipe)))
            .setGenerator(AgeGeneration.chunkGenerator(server, recipe))
            .setSeed(recipe.seed)
            // Without this an Age sits at tick zero forever: no moving sun, no stars, and `/time set` does
            // not reach it. Not `mirrorOverworldGameState()`, which would take game rules and difficulty too.
            .setMirrorOverworldClocks(true)
    }
}

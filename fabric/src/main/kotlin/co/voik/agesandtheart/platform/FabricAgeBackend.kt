package co.voik.agesandtheart.platform

import co.voik.agesandtheart.age.AgeGeneration
import co.voik.agesandtheart.platform.services.AgeBackend
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import xyz.nucleoid.fantasy.Fantasy
import xyz.nucleoid.fantasy.RuntimeWorldConfig

/**
 * Fabric runtime-dimension backend, implemented with Fantasy (NucleoidMC).
 *
 * Fantasy's persistent worlds save to disk but are not auto-restored on restart — [openAge]
 * is get-or-create, so calling it with a known id after a restart re-attaches the saved
 * chunks. Uses our custom [AgeGeneration.AGE_DIMENSION_TYPE] (registered as a datapack dimension-type
 * so its `effects` marker lets the client attach the Age sky renderer) plus our flat generator.
 */
class FabricAgeBackend : AgeBackend {
    override val isSupported: Boolean = true

    override fun openAge(server: MinecraftServer, id: ResourceLocation): ServerLevel? {
        val seed = id.hashCode().toLong()
        val config = RuntimeWorldConfig()
            .setDimensionType(ResourceKey.create(Registries.DIMENSION_TYPE, AgeGeneration.dimensionType(server, id)))
            .setGenerator(AgeGeneration.chunkGenerator(server, id, seed))
            .setSeed(seed)
        return Fantasy.get(server).getOrOpenPersistentWorld(id, config).asWorld()
    }

    /**
     * Fantasy hands out deletion through the world's *handle*, and the only route to a handle is
     * get-or-open — so an Age that is not currently loaded is briefly opened in order to be discarded.
     * Harmless, and it keeps deletion working after a restart, when nothing has been opened yet.
     */
    override fun deleteAge(server: MinecraftServer, id: ResourceLocation): Boolean {
        val seed = id.hashCode().toLong()
        val config = RuntimeWorldConfig()
            .setDimensionType(ResourceKey.create(Registries.DIMENSION_TYPE, AgeGeneration.dimensionType(server, id)))
            .setGenerator(AgeGeneration.chunkGenerator(server, id, seed))
            .setSeed(seed)
        Fantasy.get(server).getOrOpenPersistentWorld(id, config).delete()
        return true
    }
}

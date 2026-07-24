package co.voik.agesandtheart.platform

import co.voik.agesandtheart.age.AgeGen
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
 * chunks. Uses our custom [AgeGen.AGE_DIMENSION_TYPE] (registered as a datapack dimension-type
 * so its `effects` marker lets the client attach the Age sky renderer) plus our flat generator.
 */
class FabricAgeBackend : AgeBackend {
    override val isSupported: Boolean = true

    override fun openAge(server: MinecraftServer, id: ResourceLocation): ServerLevel? {
        val config = RuntimeWorldConfig()
            .setDimensionType(ResourceKey.create(Registries.DIMENSION_TYPE, AgeGen.AGE_DIMENSION_TYPE))
            .setGenerator(AgeGen.chunkGenerator(server))
            .setSeed(id.hashCode().toLong())
        return Fantasy.get(server).getOrOpenPersistentWorld(id, config).asWorld()
    }
}

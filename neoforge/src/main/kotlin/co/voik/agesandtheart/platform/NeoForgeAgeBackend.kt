package co.voik.agesandtheart.platform

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.platform.services.AgeBackend
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel

/**
 * NeoForge has no runtime-dimension backend yet — Ages are Fabric-only for now (Fantasy is
 * Fabric-only). When a cross-loader or NeoForge-native path is chosen, replace this stub.
 */
class NeoForgeAgeBackend : AgeBackend {
    override val isSupported: Boolean = false

    override fun openAge(server: MinecraftServer, id: ResourceLocation): ServerLevel? {
        Constants.LOG.warn("Runtime Ages aren't supported on NeoForge yet (requested {})", id)
        return null
    }

    override fun deleteAge(server: MinecraftServer, id: ResourceLocation): Boolean {
        Constants.LOG.warn("Runtime Ages aren't supported on NeoForge yet (asked to delete {})", id)
        return false
    }
}

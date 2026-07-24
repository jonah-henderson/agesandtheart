package co.voik.agesandtheart.platform

import co.voik.agesandtheart.platform.services.Platform
import net.fabricmc.loader.api.FabricLoader

class FabricPlatform : Platform {
    override val name: String = "Fabric"
    override val isDevelopment: Boolean get() = FabricLoader.getInstance().isDevelopmentEnvironment
    override fun isModLoaded(modId: String?): Boolean = FabricLoader.getInstance().isModLoaded(modId)
}

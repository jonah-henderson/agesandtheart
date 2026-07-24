package co.voik.agesandtheart.platform

import co.voik.agesandtheart.platform.services.Platform
import net.neoforged.fml.ModList
import net.neoforged.fml.loading.FMLLoader

class NeoForgePlatform : Platform {
    override val name: String = "NeoForge"
    override val isDevelopment: Boolean get() = !FMLLoader.isProduction()
    override fun isModLoaded(modId: String?): Boolean = ModList.get().isLoaded(modId)
}

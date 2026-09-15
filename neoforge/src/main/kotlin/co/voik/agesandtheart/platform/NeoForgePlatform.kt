package co.voik.agesandtheart.platform

import co.voik.agesandtheart.platform.services.Platform
import net.neoforged.fml.ModList
import net.neoforged.fml.loading.FMLLoader

class NeoForgePlatform : Platform {
    /**
     * `isProduction` is an instance method on the running loader now, not a static. Read through
     * `getCurrentOrNull` rather than `getCurrent`, which throws: anything asking before the loader exists
     * is not in a dev sandbox, so the absent case answers false.
     */
    override val isDevelopment: Boolean get() = FMLLoader.getCurrentOrNull()?.isProduction() == false
    override fun isModLoaded(modId: String?): Boolean = ModList.get().isLoaded(modId)
}

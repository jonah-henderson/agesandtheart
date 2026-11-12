package co.voik.agesandtheart

import co.voik.agesandtheart.client.settings.ClothPresence
import co.voik.agesandtheart.client.settings.ClothSettings
import net.neoforged.fml.ModContainer
import net.neoforged.neoforge.client.gui.ConfigurationScreen
import net.neoforged.neoforge.client.gui.IConfigScreenFactory

/**
 * The Config button on the Mods page, opening Cloth Config's screen of our settings where Cloth is installed
 * ([ClothSettings]) and NeoForge's own where it is not — **a class of its own so a dedicated server never has to load
 * it.**
 *
 * A screen extends `Screen`, which NeoForge's dev dist cleaner refuses to load outside a
 * client. A reference to it anywhere in the mod's entrypoint is resolved while that class is verified,
 * which is *before* any `Dist` check of ours could run — so guarding the call is not enough, and the name
 * has to live somewhere the server never touches. It is touched here and only here.
 */
internal object ConfigScreen {
    fun offer(modContainer: ModContainer) {
        modContainer.registerExtensionPoint(
            IConfigScreenFactory::class.java,
            IConfigScreenFactory { container, parent ->
                if (ClothPresence.isLoaded()) ClothSettings.screen(parent) else ConfigurationScreen(container, parent)
            },
        )
    }
}

package co.voik.agesandtheart

import net.neoforged.fml.ModContainer
import net.neoforged.neoforge.client.gui.ConfigurationScreen
import net.neoforged.neoforge.client.gui.IConfigScreenFactory

/**
 * The Config button on the Mods page — **a class of its own so a dedicated server never has to load it.**
 *
 * `ConfigurationScreen` extends `Screen`, which NeoForge's dev dist cleaner refuses to load outside a
 * client. A reference to it anywhere in the mod's entrypoint is resolved while that class is verified,
 * which is *before* any `Dist` check of ours could run — so guarding the call is not enough, and the name
 * has to live somewhere the server never touches. It is touched here and only here.
 */
internal object ConfigScreen {
    fun offer(modContainer: ModContainer) {
        modContainer.registerExtensionPoint(IConfigScreenFactory::class.java, IConfigScreenFactory(::ConfigurationScreen))
    }
}

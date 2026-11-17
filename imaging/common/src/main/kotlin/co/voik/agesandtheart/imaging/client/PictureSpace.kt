package co.voik.agesandtheart.imaging.client

import co.voik.agesandtheart.imaging.PictureStore
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.client.gui.ConfigurationScreen.ConfigurationSectionScreen

/**
 * The settings screen's two caps on pictures' space, each labelled with how much they take now: the world's, where this
 * game hosts it, and this client's own. Handed to the screen as its filter, which sees every option as it is laid out.
 */
object PictureSpace {

    private const val WORLD_CAP = "picturesMayTakeMegabytes"
    private const val CLIENT_CAP = "cacheMegabytes"
    private const val TAKEN = "config.agesandtheart_imaging.megabytes_taken"
    private const val BYTES_A_MEGABYTE = 1024.0 * 1024.0

    val SHOWN = ConfigurationSectionScreen.Filter { _, key, original ->
        val taken = when (key) {
            WORLD_CAP -> Minecraft.getInstance().singleplayerServer?.let(PictureStore::bytesTaken)
            CLIENT_CAP -> PictureTextures.cacheBytesTaken()
            else -> null
        }
        if (taken == null) original else labelled(original, taken)
    }

    private fun labelled(original: ConfigurationSectionScreen.Element, bytes: Long): ConfigurationSectionScreen.Element {
        val megabytes = "%.1f".format(bytes / BYTES_A_MEGABYTE)
        return ConfigurationSectionScreen.Element(
            Component.translatable(TAKEN, megabytes), original.tooltip(), original.widget(), original.option(), original.undoable(),
        )
    }
}

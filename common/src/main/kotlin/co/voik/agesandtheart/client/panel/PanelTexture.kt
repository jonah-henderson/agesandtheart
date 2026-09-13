package co.voik.agesandtheart.client.panel

import co.voik.agesandtheart.location
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.resources.Identifier

/**
 * The panel's newest field under a name, so a world render type can sample it as it would any texture.
 *
 * A render type names its textures and looks each one up afresh at every draw (`RenderSetup.getTextures`),
 * so answering with whatever [PanelTarget] drew last is the whole of it: nothing is copied, and a field
 * turning over is picked up by the next draw. The fields are [PanelTarget]'s to make and keep, which is why
 * [close] leaves them alone.
 */
object PanelTexture : AbstractTexture() {

    val LOCATION: Identifier = "linking_panel".location()

    /** Whether the texture manager knows the name yet. Render thread only, like the manager itself. */
    private var registered = false

    /** Makes the name good, on the first draw that needs it, so it never depends on how start-up runs. */
    fun register() {
        if (registered) return
        Minecraft.getInstance().textureManager.register(LOCATION, this)
        registered = true
    }

    override fun getTextureView(): GpuTextureView =
        PanelTarget.newestView() ?: error("The linking panel was sampled before anything was drawn into it")

    override fun getTexture(): GpuTexture = getTextureView().texture()

    override fun close() = Unit
}

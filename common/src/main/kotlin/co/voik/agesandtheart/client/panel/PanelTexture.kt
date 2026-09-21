package co.voik.agesandtheart.client.panel

import co.voik.agesandtheart.location
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.resources.Identifier

/**
 * One of a panel's surfaces under a name, so a render type can sample it as it would any texture.
 *
 * A render type names its textures and looks each one up afresh at every draw (`RenderSetup.getTextures`),
 * so answering with whatever is current is the whole of it: nothing is copied, and a field turning over or a
 * composite laid down again is picked up by the next draw. None of them is this class's to close — the fields
 * are [PanelTarget]'s and the composites [PanelComposite]'s.
 */
class PanelTexture private constructor(path: String, private val current: () -> GpuTextureView?) : AbstractTexture() {

    val location: Identifier = path.location()

    override fun getTextureView(): GpuTextureView =
        current() ?: error("$location was sampled before anything was drawn into it")

    override fun getTexture(): GpuTexture = getTextureView().texture()

    override fun close() = Unit

    companion object {
        val NEWEST_FIELD = PanelTexture("linking_panel/newest_field") { PanelTarget.newestView() }

        /** The older field, or the newest while a book's first shot is all there is. */
        val OLDER_FIELD = PanelTexture("linking_panel/older_field") {
            PanelTarget.olderView() ?: PanelTarget.newestView()
        }

        val LIVE = PanelTexture("linking_panel/live") { PanelComposite.liveView() }

        val MISTED = PanelTexture("linking_panel/misted") { PanelComposite.mistedView() }

        /**
         * Makes the names good. On every composite rather than once: registering what is already registered is
         * a map write that changes nothing, and it outlasts anything that ever empties the manager.
         */
        fun registerAll() {
            val manager = Minecraft.getInstance().textureManager
            for (texture in listOf(NEWEST_FIELD, OLDER_FIELD, LIVE, MISTED)) manager.register(texture.location, texture)
        }
    }
}

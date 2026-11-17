package co.voik.agesandtheart.imaging

import co.voik.agesandtheart.imaging.client.HungPictureRenderer
import co.voik.agesandtheart.imaging.client.PictureTextures
import co.voik.agesandtheart.imaging.client.ViewfinderView
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry

fun initClient() {
    ViewfinderView.install()
    ClientTickEvents.END_CLIENT_TICK.register(ViewfinderView::tick)
    // Fabric's own registry rather than vanilla's, which is private and widened only by a widener this project leaves
    // to the loader.
    @Suppress("DEPRECATION")
    EntityRendererRegistry.register(ImagingContent.HUNG_PICTURE) { HungPictureRenderer(it) }
    ClientPlayNetworking.registerGlobalReceiver(PictureSent.TYPE) { payload, _ -> PictureTextures.received(payload) }
    ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> PictureTextures.forget() }
}

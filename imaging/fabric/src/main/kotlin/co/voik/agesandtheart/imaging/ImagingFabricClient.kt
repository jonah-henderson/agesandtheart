package co.voik.agesandtheart.imaging

import co.voik.agesandtheart.imaging.client.HungPictureRenderer
import co.voik.agesandtheart.imaging.client.MaskedPictures
import co.voik.agesandtheart.imaging.client.BlurredPictures
import co.voik.agesandtheart.imaging.client.CarouselParts
import co.voik.agesandtheart.imaging.client.PictureSpace
import fuzs.forgeconfigapiport.fabric.api.v5.client.ConfigScreenFactoryRegistry
import net.neoforged.neoforge.client.gui.ConfigurationScreen
import co.voik.agesandtheart.imaging.client.PictureTextures
import co.voik.agesandtheart.imaging.client.ProjectorLights
import co.voik.agesandtheart.imaging.client.RecorderPreview
import net.fabricmc.fabric.api.resource.v1.ResourceLoader
import net.minecraft.server.packs.PackType
import co.voik.agesandtheart.imaging.client.ProjectorThrows
import co.voik.agesandtheart.imaging.client.RecordedImageRenderer
import net.minecraft.client.renderer.special.SpecialModelRenderers
import co.voik.agesandtheart.imaging.client.SlideCarouselRenderer
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry
import co.voik.agesandtheart.imaging.client.ViewfinderView
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry

fun initClient() {
    ViewfinderView.install()
    ConfigScreenFactoryRegistry.INSTANCE.register(Imaging.MOD_ID) { modId, parent -> ConfigurationScreen(modId, parent, PictureSpace.SHOWN) }
    ClientTickEvents.END_CLIENT_TICK.register(ViewfinderView::tick)
    // Fabric's own registry rather than vanilla's, which is private and widened only by a widener this project leaves
    // to the loader.
    @Suppress("DEPRECATION")
    EntityRendererRegistry.register(ImagingContent.HUNG_PICTURE) { HungPictureRenderer(it) }
    @Suppress("DEPRECATION")
    BlockEntityRendererRegistry.register(ImagingContent.SLIDE_CAROUSEL_ENTITY) { SlideCarouselRenderer(it) }
    ClientPlayNetworking.registerGlobalReceiver(PictureSent.TYPE) { payload, _ -> PictureTextures.received(payload) }
    ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
        PictureTextures.forget()
        ProjectorThrows.forget()
        MaskedPictures.forget()
        BlurredPictures.forget()
        RecorderPreview.forget()
    }
    SpecialModelRenderers.ID_MAPPER.put(Imaging.id("recorded_image"), RecordedImageRenderer.Unbaked.MAP_CODEC)
    ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(ProjectorLights.ID, ProjectorLights)
    ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(CarouselParts.ID, CarouselParts)
}

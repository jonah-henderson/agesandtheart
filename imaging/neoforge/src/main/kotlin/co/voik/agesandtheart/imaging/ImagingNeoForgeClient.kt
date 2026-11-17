package co.voik.agesandtheart.imaging

import co.voik.agesandtheart.imaging.client.HungPictureRenderer
import co.voik.agesandtheart.imaging.client.MaskedPictures
import co.voik.agesandtheart.imaging.client.PictureTextures
import co.voik.agesandtheart.imaging.client.ProjectorLights
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent
import co.voik.agesandtheart.imaging.client.ProjectorThrows
import co.voik.agesandtheart.imaging.client.RecordedImageRenderer
import net.neoforged.neoforge.client.event.RegisterSpecialModelRendererEvent
import co.voik.agesandtheart.imaging.client.SlideCarouselRenderer
import co.voik.agesandtheart.imaging.client.ViewfinderView
import net.minecraft.client.Minecraft
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent
import net.neoforged.neoforge.common.NeoForge

/** The companion's client entrypoint on NeoForge: a second `@Mod` of the same id, loaded only on a client. */
@Mod(value = Imaging.MOD_ID, dist = [Dist.CLIENT])
class ImagingNeoForgeClient(eventBus: IEventBus) {
    init {
        ViewfinderView.install()
        NeoForge.EVENT_BUS.addListener { _: ClientTickEvent.Post -> ViewfinderView.tick(Minecraft.getInstance()) }
        NeoForge.EVENT_BUS.addListener { _: ClientPlayerNetworkEvent.LoggingOut ->
            PictureTextures.forget()
            ProjectorThrows.forget()
            MaskedPictures.forget()
        }
        eventBus.addListener { event: AddClientReloadListenersEvent -> event.addListener(ProjectorLights.ID, ProjectorLights) }
        eventBus.addListener { event: RegisterSpecialModelRendererEvent ->
            event.register(Imaging.id("recorded_image"), RecordedImageRenderer.Unbaked.MAP_CODEC)
        }
        eventBus.addListener { event: EntityRenderersEvent.RegisterRenderers ->
            event.registerEntityRenderer(ImagingContent.HUNG_PICTURE) { HungPictureRenderer(it) }
            event.registerBlockEntityRenderer(ImagingContent.SLIDE_CAROUSEL_ENTITY) { SlideCarouselRenderer(it) }
        }
        eventBus.addListener { event: RegisterClientPayloadHandlersEvent ->
            event.register(PictureSent.TYPE) { payload, _ -> PictureTextures.received(payload) }
        }
    }
}

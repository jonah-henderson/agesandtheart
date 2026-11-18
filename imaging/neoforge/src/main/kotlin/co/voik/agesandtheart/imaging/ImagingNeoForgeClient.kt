package co.voik.agesandtheart.imaging

import co.voik.agesandtheart.imaging.client.HungPictureRenderer
import co.voik.agesandtheart.imaging.client.MaskedPictures
import co.voik.agesandtheart.imaging.client.BlurredPictures
import co.voik.agesandtheart.imaging.client.CarouselParts
import co.voik.agesandtheart.imaging.client.Darkroom
import co.voik.agesandtheart.imaging.client.ImageRecorderScreen
import co.voik.agesandtheart.imaging.client.MountedRecorderRenderer
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent
import co.voik.agesandtheart.imaging.client.PictureSpace
import net.neoforged.fml.ModContainer
import net.neoforged.neoforge.client.gui.ConfigurationScreen
import net.neoforged.neoforge.client.gui.IConfigScreenFactory
import co.voik.agesandtheart.imaging.client.PictureTextures
import co.voik.agesandtheart.imaging.client.ProjectorLights
import co.voik.agesandtheart.imaging.client.RecorderPreview
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
class ImagingNeoForgeClient(eventBus: IEventBus, modContainer: ModContainer) {
    init {
        ViewfinderView.install()
        modContainer.registerExtensionPoint(
            IConfigScreenFactory::class.java,
            IConfigScreenFactory { container, parent -> ConfigurationScreen(container, parent, PictureSpace.SHOWN) },
        )
        NeoForge.EVENT_BUS.addListener { _: ClientTickEvent.Post -> ViewfinderView.tick(Minecraft.getInstance()) }
        NeoForge.EVENT_BUS.addListener { _: ClientTickEvent.Post -> Darkroom.tick() }
        NeoForge.EVENT_BUS.addListener { _: ClientPlayerNetworkEvent.LoggingOut ->
            PictureTextures.forget()
            ProjectorThrows.forget()
            MaskedPictures.forget()
            BlurredPictures.forget()
            RecorderPreview.forget()
            Darkroom.forget()
        }
        eventBus.addListener { event: AddClientReloadListenersEvent ->
            event.addListener(ProjectorLights.ID, ProjectorLights)
            event.addListener(CarouselParts.ID, CarouselParts)
        }
        eventBus.addListener { event: RegisterSpecialModelRendererEvent ->
            event.register(Imaging.id("recorded_image"), RecordedImageRenderer.Unbaked.MAP_CODEC)
        }
        eventBus.addListener { event: EntityRenderersEvent.RegisterRenderers ->
            event.registerEntityRenderer(ImagingContent.HUNG_PICTURE) { HungPictureRenderer(it) }
            event.registerBlockEntityRenderer(ImagingContent.SLIDE_CAROUSEL_ENTITY) { SlideCarouselRenderer(it) }
            event.registerBlockEntityRenderer(ImagingContent.IMAGE_RECORDER_ENTITY) { MountedRecorderRenderer(it) }
        }
        eventBus.addListener { event: RegisterMenuScreensEvent ->
            event.register(ImagingContent.IMAGE_RECORDER_MENU, ::ImageRecorderScreen)
        }
        eventBus.addListener { event: RegisterClientPayloadHandlersEvent ->
            event.register(PictureSent.TYPE) { payload, _ -> PictureTextures.received(payload) }
            event.register(PhotographWanted.TYPE) { payload, _ -> Darkroom.wanted(payload) }
            event.register(LookThroughTheRecorder.TYPE) { payload, _ -> RecorderPreview.start(payload.pos) }
            event.register(PhotographChunk.TYPE) { payload, _ -> Darkroom.chunk(payload) }
            event.register(PhotographEntities.TYPE) { payload, _ -> Darkroom.entities(payload) }
        }
    }
}

package co.voik.agesandtheart

import co.voik.agesandtheart.client.AgeLooks
import co.voik.agesandtheart.age.consequence.Wounds
import co.voik.agesandtheart.client.BookEntityRenderer
import co.voik.agesandtheart.client.SandColumnRenderer
import co.voik.agesandtheart.client.VolcanicBombRenderer
import co.voik.agesandtheart.client.ClientDeskNetwork
import co.voik.agesandtheart.client.StarFissureRenderer
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import co.voik.agesandtheart.client.DeskModel
import co.voik.agesandtheart.client.KnownWords
import co.voik.agesandtheart.client.panel.LinkingPanel
import co.voik.agesandtheart.client.InkCaseScreen
import co.voik.agesandtheart.client.SupplyBinScreen
import co.voik.agesandtheart.client.WritersDeskScreen
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.client.AgeTints
import co.voik.agesandtheart.client.Storms
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent
import net.minecraft.client.gui.screens.MenuScreens
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import co.voik.agesandtheart.content.AgeFluids
import co.voik.agesandtheart.platform.NeoForgeInkFluids
import net.minecraft.client.color.block.BlockTintSource
import net.minecraft.client.renderer.block.FluidModel
import net.minecraft.client.resources.model.sprite.Material
import net.minecraft.resources.Identifier
import net.neoforged.neoforge.client.event.RegisterFluidModelsEvent
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.common.NeoForge

/**
 * NeoForge's client entrypoint: a second `@Mod` with the same id and `dist = [Dist.CLIENT]`, so nothing
 * here loads on a dedicated server.
 *
 * **The sky needs no registration on either loader now.** It is drawn by the Mixin on
 * `SkyRenderer.renderSunMoonAndStars`, which lives in `common` and is listed in both loaders' Mixin
 * configs — so what used to be this loader's startup-only `DimensionSpecialEffects` problem has simply
 * stopped existing.
 *
 * Both loaders open Ages now, so nothing here is Fabric's alone.
 */
@Mod(value = Constants.MOD_ID, dist = [Dist.CLIENT])
class AgesAndTheArtClient(eventBus: IEventBus) {
    init {
        // The Art's skies, clouds and air, handed to the library that owns those seams.
        AgeLooks.register()
        Constants.LOG.info("Ages client init (NeoForge)")
        // Forgetting the cache is a game-bus concern: these dimension keys mean nothing on the next server,
        // and an Age id can be reused, so one world's sky could otherwise appear in another's.
        NeoForge.EVENT_BUS.addListener(::onLoggingOut)
        eventBus.addListener(::onRegisterScreens)
        eventBus.addListener(::onRegisterBlockTints)
        eventBus.addListener(::onRegisterFluidModels)
        eventBus.addListener(::onRegisterRenderers)
        ClientDeskNetwork.sender = { payload -> ClientPacketDistributor.sendToServer(payload) }
        // Which of the two winds is playing has to be re-asked as a player walks in and out of shelter, so
        // it rides the client tick rather than the payload.
        NeoForge.EVENT_BUS.addListener(::onClientTick)
    }

    private fun onClientTick(event: ClientTickEvent.Post) {
        Storms.heard(net.minecraft.client.Minecraft.getInstance())
        Storms.blow(net.minecraft.client.Minecraft.getInstance())
    }

    private fun onRegisterScreens(event: RegisterMenuScreensEvent) {
        event.register(AgeContent.WRITERS_DESK_MENU, ::WritersDeskScreen)
        event.register(AgeContent.INK_CASE_MENU, ::InkCaseScreen)
        event.register(AgeContent.SUPPLY_BIN_MENU, ::SupplyBinScreen)
        // Vanilla's own container screen: a toolbox is a chest's grid with a fence on what may go in it,
        // and the fence lives in the menu rather than in the drawing.
        event.register(AgeContent.TOOLBOX_MENU, ::ContainerScreen)
    }

    /**
     * The tints our block textures do not carry, at the one moment `BlockColors` exists and nothing has
     * baked against it yet — this event is fired from inside `BlockColors.createDefault`.
     */
    private fun onRegisterBlockTints(event: RegisterColorHandlersEvent.BlockTintSources) {
        AgeTints.register { sources, block -> event.register(sources, block) }
    }

    /**
     * What ink looks like in the world: water's textures, tinted per ink. Without it a pool draws as the
     * missing texture, since 26.1 renders fluids from a model rather than from a handler.
     */
    private fun onRegisterRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerEntityRenderer(AgeContent.BOOK_ENTITY, ::BookEntityRenderer)
        event.registerEntityRenderer(AgeContent.SAND_COLUMN, ::SandColumnRenderer)
        event.registerEntityRenderer(AgeContent.VOLCANIC_BOMB, ::VolcanicBombRenderer)
        // The wound's flicker and the fissure's shaft, both block entities drawn by shader rather than by
        // a baked model — the same event on this loader, where Fabric has a registry of its own.
        event.registerBlockEntityRenderer(AgeContent.STAR_FISSURE_ENTITY) { StarFissureRenderer() }
    }

    private fun onRegisterFluidModels(event: RegisterFluidModelsEvent) {
        for ((tier, identity) in AgeFluids.INKS) {
            val model = FluidModel.Unbaked(
                Material(Identifier.withDefaultNamespace("block/water_still")),
                Material(Identifier.withDefaultNamespace("block/water_flow")),
                null,
                BlockTintSource { identity.tint },
            )
            event.register(model, NeoForgeInkFluids.still(tier), NeoForgeInkFluids.flowing(tier))
        }
    }

    private fun onLoggingOut(event: ClientPlayerNetworkEvent.LoggingOut) {
        KnownWords.forgetAll()
        DeskModel.forget()
        Wounds.forget()
        // A book open when the connection drops never reaches `Screen.removed`, so its preview level and
        // renderer would outlive the connection that fed them. Fabric forgets in its own entrypoint.
        LinkingPanel.forget()
    }
}

package co.voik.agesandtheart

import co.voik.agesandtheart.client.BookEntityRenderer
import co.voik.agesandtheart.client.ClientDeskNetwork
import co.voik.agesandtheart.client.StarFissureRenderer
import co.voik.agesandtheart.client.WoundRenderer
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import co.voik.agesandtheart.client.DeskModel
import co.voik.agesandtheart.client.KnownWords
import co.voik.agesandtheart.client.Wounds
import co.voik.agesandtheart.client.InkCaseScreen
import co.voik.agesandtheart.client.SupplyBinScreen
import co.voik.agesandtheart.client.WritersDeskScreen
import co.voik.agesandtheart.content.AgeContent
import net.minecraft.client.gui.screens.MenuScreens
import co.voik.agesandtheart.content.AgeFluids
import co.voik.agesandtheart.platform.NeoForgeInkFluids
import net.minecraft.client.color.block.BlockTintSource
import net.minecraft.client.renderer.block.FluidModel
import net.minecraft.client.resources.model.sprite.Material
import net.minecraft.resources.Identifier
import net.neoforged.neoforge.client.event.RegisterFluidModelsEvent
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import co.voik.agesandtheart.sky.KnownLooks
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
 * **The Spire is still Fabric-only**, NeoForge being unable to open an Age at all while Fantasy is.
 */
@Mod(value = Constants.MOD_ID, dist = [Dist.CLIENT])
class AgesAndTheArtClient(eventBus: IEventBus) {
    init {
        Constants.LOG.info("Ages client init (NeoForge)")
        // Forgetting the cache is a game-bus concern: these dimension keys mean nothing on the next server,
        // and an Age id can be reused, so one world's sky could otherwise appear in another's.
        NeoForge.EVENT_BUS.addListener(::onLoggingOut)
        eventBus.addListener(::onRegisterScreens)
        eventBus.addListener(::onRegisterFluidModels)
        eventBus.addListener(::onRegisterRenderers)
        ClientDeskNetwork.sender = { payload -> ClientPacketDistributor.sendToServer(payload) }
    }

    private fun onRegisterScreens(event: RegisterMenuScreensEvent) {
        event.register(AgeContent.WRITERS_DESK_MENU, ::WritersDeskScreen)
        event.register(AgeContent.INK_CASE_MENU, ::InkCaseScreen)
        event.register(AgeContent.SUPPLY_BIN_MENU, ::SupplyBinScreen)
    }

    /**
     * What ink looks like in the world: water's textures, tinted per ink. Without it a pool draws as the
     * missing texture, since 26.1 renders fluids from a model rather than from a handler.
     */
    private fun onRegisterRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerEntityRenderer(AgeContent.BOOK_ENTITY, ::BookEntityRenderer)
        // The wound's flicker and the fissure's shaft, both block entities drawn by shader rather than by
        // a baked model — the same event on this loader, where Fabric has a registry of its own.
        event.registerBlockEntityRenderer(AgeContent.WOUND_ENTITY) { WoundRenderer() }
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
        KnownLooks.forgetAll()
        KnownWords.forgetAll()
        DeskModel.forget()
        Wounds.forget()
    }
}

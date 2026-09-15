package co.voik.agesandtheart

import co.voik.agesandtheart.client.AgeFluidLooks
import co.voik.agesandtheart.client.AgeLooks
import co.voik.agesandtheart.client.ClientPayloads
import co.voik.agesandtheart.client.ClientRegistrations
import co.voik.agesandtheart.client.ClientSetup
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import co.voik.agesandtheart.client.AgeTints
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.MenuAccess
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.world.entity.Entity
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.level.block.entity.BlockEntity
import net.neoforged.neoforge.event.level.ChunkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent
import co.voik.agesandtheart.content.AgeFluids
import co.voik.agesandtheart.platform.NeoForgeDeepWater
import co.voik.agesandtheart.platform.NeoForgeInkFluids
import net.neoforged.neoforge.client.event.RegisterFluidModelsEvent
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.common.NeoForge

/**
 * NeoForge's client entrypoint: a second `@Mod` with the same id and `dist = [Dist.CLIENT]`, so nothing
 * here loads on a dedicated server.
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
        // The clientbound payloads' handlers, which the main class registers those payloads without.
        eventBus.addListener(::onRegisterPayloadHandlers)
        NeoForge.EVENT_BUS.addListener(::onClientTick)
        // A client level's chunks. **Registered here rather than in the main class**, whose listener takes
        // server levels only: what these feed reaches client classes a dedicated server does not have.
        NeoForge.EVENT_BUS.addListener(::onChunkLoad)
        NeoForge.EVENT_BUS.addListener(::onChunkUnload)
    }

    private fun onChunkLoad(event: ChunkEvent.Load) {
        val level = event.level as? ClientLevel ?: return
        ClientSetup.chunkLoaded(level, event.chunk)
    }

    private fun onChunkUnload(event: ChunkEvent.Unload) {
        val level = event.level as? ClientLevel ?: return
        ClientSetup.chunkUnloaded(level, event.chunk.pos)
    }

    private fun onClientTick(event: ClientTickEvent.Post) {
        ClientSetup.clientTick(Minecraft.getInstance())
    }

    private fun onRegisterScreens(event: RegisterMenuScreensEvent) {
        ClientRegistrations.MENU_SCREENS.forEach { registerScreen(event, it) }
    }

    /**
     * The tints our block textures do not carry, at the one moment `BlockColors` exists and nothing has
     * baked against it yet — this event is fired from inside `BlockColors.createDefault`.
     */
    private fun onRegisterBlockTints(event: RegisterColorHandlersEvent.BlockTintSources) {
        AgeTints.register { sources, block -> event.register(sources, block) }
    }

    private fun onRegisterRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        ClientRegistrations.ENTITY_RENDERERS.forEach { registerEntityRenderer(event, it) }
        ClientRegistrations.BLOCK_ENTITY_RENDERERS.forEach { registerBlockEntityRenderer(event, it) }
    }

    private fun onRegisterFluidModels(event: RegisterFluidModelsEvent) {
        for ((tier, identity) in AgeFluids.INKS) {
            event.register(AgeFluidLooks.ink(identity), NeoForgeInkFluids.still(tier), NeoForgeInkFluids.flowing(tier))
        }
        event.register(AgeFluidLooks.deepWater(), NeoForgeDeepWater.still, NeoForgeDeepWater.flowing)
    }

    /** Each handler runs on the client thread, which is this event's default. */
    private fun onRegisterPayloadHandlers(event: RegisterClientPayloadHandlersEvent) {
        ClientPayloads.RECEIVERS.forEach { registerReceiver(event, it) }
    }

    private fun onLoggingOut(event: ClientPlayerNetworkEvent.LoggingOut) {
        ClientSetup.disconnected()
    }

    private fun <T : Entity> registerEntityRenderer(
        event: EntityRenderersEvent.RegisterRenderers,
        entry: ClientRegistrations.RendererForEntity<T>,
    ) {
        event.registerEntityRenderer(entry.type, entry.provider)
    }

    private fun <T : BlockEntity, S : BlockEntityRenderState> registerBlockEntityRenderer(
        event: EntityRenderersEvent.RegisterRenderers,
        entry: ClientRegistrations.RendererForBlockEntity<T, S>,
    ) {
        event.registerBlockEntityRenderer(entry.type, entry.provider)
    }

    private fun <M : AbstractContainerMenu, U> registerScreen(
        event: RegisterMenuScreensEvent,
        entry: ClientRegistrations.ScreenForMenu<M, U>,
    ) where U : Screen, U : MenuAccess<M> {
        event.register(entry.menu) { menu, inventory, title -> entry.screen(menu, inventory, title) }
    }

    private fun <T : CustomPacketPayload> registerReceiver(
        event: RegisterClientPayloadHandlersEvent,
        receiver: ClientPayloads.Receiver<T>,
    ) {
        event.register(receiver.type) { payload, _ -> receiver.receive(payload) }
    }
}

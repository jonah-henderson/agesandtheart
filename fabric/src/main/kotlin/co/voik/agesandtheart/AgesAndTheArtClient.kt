package co.voik.agesandtheart

import co.voik.agesandtheart.client.AgeFluidLooks
import co.voik.agesandtheart.client.ClientPayloads
import co.voik.agesandtheart.client.ClientRegistrations
import co.voik.agesandtheart.client.ClientSetup
import net.fabricmc.fabric.api.client.rendering.v1.BlockColorRegistry
import co.voik.agesandtheart.client.AgeLooks
import co.voik.agesandtheart.client.AgeTints
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import co.voik.agesandtheart.content.AgeFluids
import co.voik.agesandtheart.platform.FabricDeepWaterFluids
import co.voik.agesandtheart.platform.FabricInkFluids
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderingRegistry
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.gui.screens.MenuScreens
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.MenuAccess
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.renderer.entity.EntityRenderers
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.world.entity.Entity
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.level.block.entity.BlockEntity

/** Fabric client entrypoint. */
fun initClient() {
    Constants.LOG.info("Ages client init")

    // The Art's skies, clouds and air, handed to the library that owns those seams.
    AgeLooks.register()

    for ((tier, identity) in AgeFluids.INKS) {
        FluidRenderingRegistry.register(FabricInkFluids.still(tier), FabricInkFluids.flowing(tier), AgeFluidLooks.ink(identity))
    }
    FluidRenderingRegistry.register(FabricDeepWaterFluids.still, FabricDeepWaterFluids.flowing, AgeFluidLooks.deepWater())
    ClientRegistrations.ENTITY_RENDERERS.forEach { registerEntityRenderer(it) }
    ClientRegistrations.BLOCK_ENTITY_RENDERERS.forEach { registerBlockEntityRenderer(it) }
    ClientRegistrations.MENU_SCREENS.forEach { registerScreen(it) }
    AgeTints.register { sources, block -> BlockColorRegistry.register(sources, block) }
    ClientPayloads.RECEIVERS.forEach { registerReceiver(it) }
    ClientTickEvents.END_CLIENT_TICK.register(ClientSetup::clientTick)
    ClientChunkEvents.CHUNK_LOAD.register { level, chunk -> ClientSetup.chunkLoaded(level, chunk) }
    ClientChunkEvents.CHUNK_UNLOAD.register { level, chunk -> ClientSetup.chunkUnloaded(level, chunk.pos) }
    ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> ClientSetup.disconnected() }
}

private fun <T : Entity> registerEntityRenderer(entry: ClientRegistrations.RendererForEntity<T>) {
    // Fabric API's `EntityRendererRegistry` is retired in favour of vanilla's own registry, which its
    // transitive access wideners open up — so there is nothing left for the helper to help with.
    EntityRenderers.register(entry.type, entry.provider)
}

private fun <T : BlockEntity, S : BlockEntityRenderState> registerBlockEntityRenderer(
    entry: ClientRegistrations.RendererForBlockEntity<T, S>,
) {
    BlockEntityRenderers.register(entry.type, entry.provider)
}

private fun <M : AbstractContainerMenu, U> registerScreen(entry: ClientRegistrations.ScreenForMenu<M, U>)
    where U : Screen, U : MenuAccess<M> {
    MenuScreens.register(entry.menu) { menu, inventory, title -> entry.screen(menu, inventory, title) }
}

/** The receiver runs on the client thread, where Fabric delivers a play payload. */
private fun <T : CustomPacketPayload> registerReceiver(receiver: ClientPayloads.Receiver<T>) {
    ClientPlayNetworking.registerGlobalReceiver(receiver.type) { payload, _ -> receiver.receive(payload) }
}

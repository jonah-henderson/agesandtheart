package co.voik.agesandtheart.imaging

import co.voik.agesandtheart.Payloads
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

fun init() {
    ImagingComponents.components.forEach { (id, type) -> Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, id, type) }
    ImagingContent.blocks.forEach { (id, block) -> Registry.register(BuiltInRegistries.BLOCK, id, block) }
    ImagingContent.items.forEach { (id, item) -> Registry.register(BuiltInRegistries.ITEM, id, item) }
    ImagingContent.entities.forEach { (id, type) -> Registry.register(BuiltInRegistries.ENTITY_TYPE, id, type) }
    CreativeModeTabEvents.modifyOutputEvent(ImagingContent.TAB).register { output ->
        ImagingContent.shownInTheTab.forEach(output::accept)
    }
    ImagingPayloads.ROUTES.forEach { registerPayload(it) }
    ServerTickEvents.END_SERVER_TICK.register(Viewfinder::serverTick)
}

/** As Ages and the Art registers its own: a client's payload is handled on the server thread. */
private fun <T : CustomPacketPayload> registerPayload(route: Payloads.Route<T>) {
    when (route) {
        is Payloads.Clientbound -> PayloadTypeRegistry.clientboundPlay().register(route.type, route.codec)
        is Payloads.Serverbound -> {
            PayloadTypeRegistry.serverboundPlay().register(route.type, route.codec)
            ServerPlayNetworking.registerGlobalReceiver(route.type) { payload, context ->
                context.server().execute { route.handle(context.player(), payload) }
            }
        }
    }
}

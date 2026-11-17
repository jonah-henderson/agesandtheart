package co.voik.agesandtheart.imaging

import co.voik.agesandtheart.Payloads
import net.minecraft.core.registries.Registries
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.fml.config.ModConfig
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.registration.PayloadRegistrar
import net.neoforged.neoforge.registries.RegisterEvent

@Mod(Imaging.MOD_ID)
class ImagingNeoForge(eventBus: IEventBus, modContainer: ModContainer) {
    init {
        eventBus.addListener(::onRegister)
        eventBus.addListener(::onBuildCreativeTab)
        eventBus.addListener(::onRegisterPayloads)
        NeoForge.EVENT_BUS.addListener { event: ServerTickEvent.Post -> Imaging.serverTick(event.server) }
        modContainer.registerConfig(ModConfig.Type.SERVER, ImagingConfig.SPEC)
    }

    private fun onRegister(event: RegisterEvent) {
        event.register(Registries.DATA_COMPONENT_TYPE) { helper ->
            ImagingComponents.components.forEach { (id, type) -> helper.register(id, type) }
        }
        event.register(Registries.BLOCK) { helper -> ImagingContent.blocks.forEach { (id, block) -> helper.register(id, block) } }
        event.register(Registries.ITEM) { helper -> ImagingContent.items.forEach { (id, item) -> helper.register(id, item) } }
        event.register(Registries.BLOCK_ENTITY_TYPE) { helper -> ImagingContent.blockEntities.forEach { (id, type) -> helper.register(id, type) } }
        event.register(Registries.SOUND_EVENT) { helper -> ImagingContent.soundEvents.forEach { (id, sound) -> helper.register(id, sound) } }
        event.register(Registries.ENTITY_TYPE) { helper -> ImagingContent.entities.forEach { (id, type) -> helper.register(id, type) } }
    }

    private fun onBuildCreativeTab(event: BuildCreativeModeTabContentsEvent) {
        if (event.tabKey != ImagingContent.TAB) return
        ImagingContent.shownInTheTab.forEach(event::accept)
    }

    private fun onRegisterPayloads(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar(PAYLOAD_VERSION)
        ImagingPayloads.ROUTES.forEach { registerPayload(registrar, it) }
    }

    private fun <T : CustomPacketPayload> registerPayload(registrar: PayloadRegistrar, route: Payloads.Route<T>) {
        when (route) {
            is Payloads.Clientbound -> registrar.playToClient(route.type, route.codec)
            is Payloads.Serverbound -> registrar.playToServer(route.type, route.codec) { payload, context ->
                (context.player() as? ServerPlayer)?.let { route.handle(it, payload) }
            }
        }
    }

    companion object {
        /** Bumped with any change to a payload's codec or meaning. */
        const val PAYLOAD_VERSION = "1"
    }
}

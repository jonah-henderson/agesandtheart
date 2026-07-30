package co.voik.agesandtheart

import co.voik.agesandtheart.age.AgeCommand
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.sky.KnownSkies
import co.voik.agesandtheart.sky.SkyPayload
import co.voik.agesandtheart.sky.Skies
import net.minecraft.core.registries.Registries
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.registries.RegisterEvent

@Mod(Constants.MOD_ID)
class AgesAndTheArt(eventBus: IEventBus, modContainer: ModContainer) {
    init {
        CommonSetup.init()

        // Content registration is a mod-bus event on NeoForge.
        eventBus.addListener(::onRegister)
        // Payload registration is a mod-bus event, so it cannot be a call from common init the way Fabric's is.
        eventBus.addListener(::onRegisterPayloads)
        // Commands are a game-bus event.
        NeoForge.EVENT_BUS.addListener(::onRegisterCommands)
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedIn)
    }

    private fun onRegister(event: RegisterEvent) {
        event.register(Registries.DATA_COMPONENT_TYPE) { helper ->
            AgeContent.components.forEach { (id, comp) -> helper.register(id, comp) }
        }
        event.register(Registries.ITEM) { helper ->
            AgeContent.items.forEach { (id, item) -> helper.register(id, item) }
        }
        event.register(Registries.CHUNK_GENERATOR) { helper ->
            AgeContent.chunkGeneratorCodecs.forEach { (id, codec) -> helper.register(id, codec) }
        }
        event.register(Registries.BIOME_SOURCE) { helper ->
            AgeContent.biomeSourceCodecs.forEach { (id, codec) -> helper.register(id, codec) }
        }
        event.register(Registries.MATERIAL_RULE) { helper ->
            AgeContent.surfaceRuleCodecs.forEach { (id, codec) -> helper.register(id, codec) }
        }
        event.register(Registries.CARVER) { helper ->
            AgeContent.carvers.forEach { (id, carver) -> helper.register(id, carver) }
        }
    }

    /**
     * The version string is what NeoForge compares between two modded ends — bump it whenever the payload's codec
     * or handler semantics change, or two versions will negotiate a channel they disagree about.
     *
     * The handler lands the spec in [KnownSkies], where `AgeSky` reads it per frame. It runs on the client — the
     * registrar's default is `HandlerThread.MAIN`, which wraps it in `context.enqueueWork` — and [KnownSkies] is
     * plain data in `common` with no client types, so nothing here is dist-sensitive. That is why this stays on the
     * server-side class rather than moving to `AgesAndTheArtClient`: Fabric likewise requires the payload *type* on
     * both sides, and splitting the two loaders' registration differently would be a difference without a reason.
     */
    private fun onRegisterPayloads(event: RegisterPayloadHandlersEvent) {
        event.registrar(PAYLOAD_VERSION).playToClient(
            SkyPayload.TYPE,
            SkyPayload.STREAM_CODEC,
        ) { payload, _ -> KnownSkies.remember(payload) }
    }

    private fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? net.minecraft.server.level.ServerPlayer ?: return
        Skies.tellAboutEverything(player)
    }

    private fun onRegisterCommands(event: RegisterCommandsEvent) {
        AgeCommand.register(event.dispatcher)
    }

    private companion object {
        const val PAYLOAD_VERSION = "1"
    }
}

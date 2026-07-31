package co.voik.agesandtheart

import co.voik.agesandtheart.age.AgeCommand
import co.voik.agesandtheart.age.Ages
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.sky.SkyPayload
import co.voik.agesandtheart.sky.Skies
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries

fun init() {
    CommonSetup.init()

    // Register content (components before items). On Fabric this is done directly during init.
    AgeContent.components.forEach { (id, comp) -> Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, id, comp) }
    AgeContent.items.forEach { (id, item) -> Registry.register(BuiltInRegistries.ITEM, id, item) }
    AgeContent.chunkGeneratorCodecs.forEach { (id, codec) -> Registry.register(BuiltInRegistries.CHUNK_GENERATOR, id, codec) }
    AgeContent.biomeSourceCodecs.forEach { (id, codec) -> Registry.register(BuiltInRegistries.BIOME_SOURCE, id, codec) }
    AgeContent.surfaceRuleCodecs.forEach { (id, codec) -> Registry.register(BuiltInRegistries.MATERIAL_RULE, id, codec) }
    AgeContent.carvers.forEach { (id, carver) -> Registry.register(BuiltInRegistries.CARVER, id, carver) }

    // The payload type, registered here rather than in the client entrypoint: Fabric requires it on *both*
    // sides, and registering twice throws. Common init is the only place that is true of.
    PayloadTypeRegistry.clientboundPlay().register(SkyPayload.TYPE, SkyPayload.STREAM_CODEC)

    // A joining player is told every Age's sky at once, so arriving by any route — book, portal, `/execute in`
    // — already has one. See `Skies.tellAboutEverything`.
    ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
        Skies.tellAboutEverything(handler.player)
    }

    // Loader-specific glue: hand the common command tree Fabric's dispatcher.
    CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
        AgeCommand.register(dispatcher)
    }

    // Re-open persisted Ages once the server has started (Fantasy doesn't auto-restore them).
    ServerLifecycleEvents.SERVER_STARTED.register { server ->
        Ages.reloadSaved(server)
    }
}

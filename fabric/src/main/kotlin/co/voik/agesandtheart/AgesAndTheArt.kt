package co.voik.agesandtheart

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import co.voik.agesandtheart.platform.FabricDeepWaterFluids
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import co.voik.agesandtheart.age.AgeCommand
import co.voik.agesandtheart.age.Ages
import co.voik.agesandtheart.age.word.PageLoot
import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.desk.WritersDeskBlock
import co.voik.agesandtheart.platform.FabricInkTank
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage
import co.voik.agesandtheart.content.AgeContent
// `object` is a Kotlin keyword and Fabric put one in the package path, so it needs quoting.
import net.fabricmc.fabric.api.`object`.builder.v1.entity.FabricDefaultAttributeRegistry
import co.voik.agesandtheart.platform.FabricInkFluids
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.loot.v3.LootTableEvents
import net.fabricmc.fabric.api.`object`.builder.v1.world.poi.PoiHelper
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import fuzs.forgeconfigapiport.fabric.api.v5.ConfigRegistry
import net.neoforged.fml.config.ModConfig
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

fun init() {
    CommonSetup.init()

    // The config. Fabric has no config system of its own, so the spec is registered through Forge Config
    // API Port, against the same `ModConfigSpec` NeoForge registers (`notes/config-research.md`).
    //
    // No screen is registered here. The port offers a `ConfigScreenFactoryRegistry` for a mod that wants
    // to supply its own, and the whole point of choosing a spec other tools can read is not needing to:
    // Configured generates one from this, and Mod Menu indexes it.
    ConfigRegistry.INSTANCE.register(Constants.MOD_ID, ModConfig.Type.SERVER, AgeConfig.SPEC)
    ConfigRegistry.INSTANCE.register(Constants.MOD_ID, ModConfig.Type.CLIENT, AgeClientLook.SPEC)

    // Register content (components before items). On Fabric this is done directly during init.
    // Fluids before items: a bucket names its fluid, and the pair is built together.
    FabricInkFluids.register()
    // Deep water is registered here rather than through `AgeContent.blocks`, because its block names a
    // fluid and only a loader can build one — see `AgeFluids.DEEP_WATER`.
    FabricDeepWaterFluids.register()

    AgeContent.components.forEach { (id, comp) -> Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, id, comp) }
    AgeContent.blocks.forEach { (id, block) -> Registry.register(BuiltInRegistries.BLOCK, id, block) }
    AgeContent.items.forEach { (id, item) -> Registry.register(BuiltInRegistries.ITEM, id, item) }
    AgeContent.entities.forEach { (id, type) -> Registry.register(BuiltInRegistries.ENTITY_TYPE, id, type) }
    AgeContent.mobAttributes.forEach { (type, attributes) -> FabricDefaultAttributeRegistry.register(type, attributes()) }
    AgeContent.soundEvents.forEach { (id, sound) -> Registry.register(BuiltInRegistries.SOUND_EVENT, id, sound) }
    AgeContent.mobEffects.forEach { (id, effect) -> Registry.register(BuiltInRegistries.MOB_EFFECT, id, effect) }
    AgeContent.tickets.forEach { (id, type) -> Registry.register(BuiltInRegistries.TICKET_TYPE, id, type) }
    AgeContent.blockEntities.forEach { (id, type) -> Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id, type) }
    AgeContent.structureTypes.forEach { (id, type) -> Registry.register(BuiltInRegistries.STRUCTURE_TYPE, id, type) }
    AgeContent.structurePieces.forEach { (id, type) ->
        Registry.register(BuiltInRegistries.STRUCTURE_PIECE, id, type)
    }
    // **Through `PoiHelper`, not `Registry.register`.** `PoiTypes` keeps its block-state map private, so
    // a plainly registered point of interest is never recognised on the ground — `PoiTypes.forState`
    // returns nothing and no villager sees the desk. NeoForge's half needs no such help.
    AgeContent.poiTypes.forEach { (id, type) ->
        PoiHelper.register(id, type.maxTickets(), type.validRange(), type.matchingStates())
    }
    AgeContent.villagerProfessions.forEach { (id, profession) ->
        Registry.register(BuiltInRegistries.VILLAGER_PROFESSION, id, profession)
    }
    AgeContent.menus.forEach { (id, type) -> Registry.register(BuiltInRegistries.MENU, id, type) }
    AgeContent.recipeSerializers.forEach { (id, serializer) ->
        Registry.register(BuiltInRegistries.RECIPE_SERIALIZER, id, serializer)
    }
    AgeContent.chunkGeneratorCodecs.forEach { (id, codec) -> Registry.register(BuiltInRegistries.CHUNK_GENERATOR, id, codec) }
    AgeContent.biomeSourceCodecs.forEach { (id, codec) -> Registry.register(BuiltInRegistries.BIOME_SOURCE, id, codec) }
    AgeContent.surfaceRuleCodecs.forEach { (id, codec) -> Registry.register(BuiltInRegistries.MATERIAL_RULE, id, codec) }
    AgeContent.surfaceConditionCodecs.forEach { (id, codec) ->
        Registry.register(BuiltInRegistries.MATERIAL_CONDITION, id, codec)
    }
    AgeContent.carvers.forEach { (id, carver) -> Registry.register(BuiltInRegistries.CARVER, id, carver) }
    AgeContent.features.forEach { (id, feature) -> Registry.register(BuiltInRegistries.FEATURE, id, feature) }
    AgeContent.lootFunctions.forEach { (id, fn) -> Registry.register(BuiltInRegistries.LOOT_FUNCTION_TYPE, id, fn) }

    // The payload types, registered here rather than in the client entrypoint: Fabric requires them on
    // *both* sides, and registering twice throws. Common init is the only place that is true of.
    Payloads.ROUTES.forEach { registerPayload(it) }
    ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
        CommonSetup.playerLeft(server, handler.player)
    }
    ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
        CommonSetup.playerJoined(handler.player)
    }

    // The desk's tanks, on every part of it — a pipe touching a wing should work, since the wings are
    // the same furniture. Registered against the block rather than the block entity for that reason.
    FluidStorage.SIDED.registerForBlocks(
        { level, pos, _, _, _ ->
            WritersDeskBlock.entityAt(level, pos)?.let { desk ->
                CombinedStorage(InkTier.entries.map { FabricInkTank(desk, it) })
            }
        },
        AgeContent.WRITERS_DESK_BLOCK,
    )

    // Pages into vanilla containers. What a find yields is the `agesandtheart:inject/pages` datapack
    // table; only which containers and how often is decided here.
    LootTableEvents.MODIFY.register { key, tableBuilder, _, _ ->
        PageLoot.targetsFor(key).forEach { tableBuilder.pool(PageLoot.poolFor(it)) }
    }

    // Loader-specific glue: hand the common command tree Fabric's dispatcher.
    CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
        AgeCommand.register(dispatcher)
    }

    // Re-open persisted Ages once the server has started (nothing auto-restores a runtime level).
    ServerLifecycleEvents.SERVER_STARTED.register { server ->
        Ages.reloadSaved(server)
    }
    ServerLifecycleEvents.SERVER_STOPPED.register { CommonSetup.serverStopped() }

    ServerTickEvents.END_SERVER_TICK.register(CommonSetup::serverTick)
    ServerChunkEvents.CHUNK_LOAD.register { level, chunk, _ -> CommonSetup.chunkLoaded(level, chunk) }
    ServerChunkEvents.CHUNK_UNLOAD.register { level, chunk -> CommonSetup.chunkUnloaded(level, chunk.pos) }

    // Last, and it has to be: everything of ours is registered by now, which is the whole condition.
    CommonSetup.afterContentRegistered()
}

/** Registers one payload's type and, for one a client sends, its receiver, which runs on the server thread. */
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

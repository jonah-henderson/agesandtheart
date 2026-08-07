package co.voik.agesandtheart

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import co.voik.agesandtheart.age.phenomena.Happenings
import co.voik.agesandtheart.age.AgeCommand
import co.voik.agesandtheart.age.Ages
import co.voik.agesandtheart.age.word.LearnedWordsPayload
import co.voik.agesandtheart.age.word.LexiconPayload
import co.voik.agesandtheart.age.word.PageLearning
import co.voik.agesandtheart.age.word.PageLoot
import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.book.LinkRequest
import co.voik.agesandtheart.book.Linking
import co.voik.agesandtheart.desk.WritersDeskBlock
import co.voik.agesandtheart.platform.FabricInkTank
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage
import co.voik.agesandtheart.desk.DeskCommandPayload
import co.voik.agesandtheart.desk.DeskCommands
import co.voik.agesandtheart.desk.DeskNoticePayload
import co.voik.agesandtheart.desk.DeskPricePayload
import co.voik.agesandtheart.desk.DeskSyncPayload
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.platform.FabricInkFluids
import co.voik.agesandtheart.sky.LookPayload
import co.voik.agesandtheart.sky.Skies
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.loot.v3.LootTableEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import fuzs.forgeconfigapiport.fabric.api.v5.ConfigRegistry
import net.neoforged.fml.config.ModConfig
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries

fun init() {
    CommonSetup.init()

    // The config. Fabric has no config system of its own, so the spec is registered through Forge Config
    // API Port, against the same `ModConfigSpec` NeoForge registers (`notes/config-research.md`).
    //
    // No screen is registered here. The port offers a `ConfigScreenFactoryRegistry` for a mod that wants
    // to supply its own, and the whole point of choosing a spec other tools can read is not needing to:
    // Configured generates one from this, and Mod Menu indexes it.
    ConfigRegistry.INSTANCE.register(Constants.MOD_ID, ModConfig.Type.SERVER, AgeConfig.SPEC)

    // Register content (components before items). On Fabric this is done directly during init.
    // Fluids before items: a bucket names its fluid, and the pair is built together.
    FabricInkFluids.register()

    AgeContent.components.forEach { (id, comp) -> Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, id, comp) }
    AgeContent.blocks.forEach { (id, block) -> Registry.register(BuiltInRegistries.BLOCK, id, block) }
    AgeContent.items.forEach { (id, item) -> Registry.register(BuiltInRegistries.ITEM, id, item) }
    AgeContent.entities.forEach { (id, type) -> Registry.register(BuiltInRegistries.ENTITY_TYPE, id, type) }
    AgeContent.blockEntities.forEach { (id, type) -> Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id, type) }
    AgeContent.structureTypes.forEach { (id, type) -> Registry.register(BuiltInRegistries.STRUCTURE_TYPE, id, type) }
    AgeContent.structurePieces.forEach { (id, type) ->
        Registry.register(BuiltInRegistries.STRUCTURE_PIECE, id, type)
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
    AgeContent.lootFunctions.forEach { (id, fn) -> Registry.register(BuiltInRegistries.LOOT_FUNCTION_TYPE, id, fn) }

    // The payload type, registered here rather than in the client entrypoint: Fabric requires it on *both*
    // sides, and registering twice throws. Common init is the only place that is true of.
    PayloadTypeRegistry.clientboundPlay().register(LookPayload.TYPE, LookPayload.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(LexiconPayload.TYPE, LexiconPayload.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(LearnedWordsPayload.TYPE, LearnedWordsPayload.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(DeskSyncPayload.TYPE, DeskSyncPayload.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(DeskPricePayload.TYPE, DeskPricePayload.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(DeskNoticePayload.TYPE, DeskNoticePayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(DeskCommandPayload.TYPE, DeskCommandPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(LinkRequest.TYPE, LinkRequest.STREAM_CODEC)

    ServerPlayNetworking.registerGlobalReceiver(LinkRequest.TYPE) { payload, context ->
        context.server().execute { Linking.handle(context.player(), payload) }
    }

    // The desk's instructions arrive here; every one of them is re-checked server-side.
    ServerPlayNetworking.registerGlobalReceiver(DeskCommandPayload.TYPE) { payload, context ->
        context.server().execute { DeskCommands.handle(context.player(), payload) }
    }

    // A joining player is told every Age's sky at once, so arriving by any route — book, portal, `/execute in`
    // — already has one. See `Skies.tellAboutEverything`.
    ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
        Skies.tellAboutEverything(handler.player)
        PageLearning.tellEverything(handler.player)
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

    // Re-open persisted Ages once the server has started (Fantasy doesn't auto-restore them).
    ServerLifecycleEvents.SERVER_STARTED.register { server ->
        Ages.reloadSaved(server)
    }

    // Whatever befalls an Age. A tick has no shared entry point, so both loaders call the same one.
    ServerTickEvents.END_SERVER_TICK.register(Happenings::tick)
}

package co.voik.agesandtheart

import co.voik.agesandtheart.age.consequence.Worsening
import co.voik.agesandtheart.age.consequence.Wounds
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import co.voik.agesandtheart.content.ChargedMetal
import co.voik.agesandtheart.content.ProtectiveSuit
import co.voik.agesandtheart.platform.FabricDeepWaterFluids
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import co.voik.agesandtheart.age.phenomena.Happenings
import co.voik.agesandtheart.age.AgeCommand
import co.voik.agesandtheart.age.Ages
import co.voik.agesandtheart.age.word.LearnedWordsPayload
import co.voik.agesandtheart.age.phenomena.BlizzardPayload
import co.voik.agesandtheart.age.word.LexiconPayload
import co.voik.agesandtheart.age.word.PageLearning
import co.voik.agesandtheart.age.word.PageLoot
import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.book.LinkRequest
import co.voik.agesandtheart.book.panel.PanelChunkPayload
import co.voik.agesandtheart.book.panel.PanelChunksWanted
import co.voik.agesandtheart.book.panel.PanelCloseRequest
import co.voik.agesandtheart.book.panel.PanelLevelPayload
import co.voik.agesandtheart.book.panel.PanelOpenRequest
import co.voik.agesandtheart.book.panel.PanelViews
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
import co.voik.agesandtheart.content.AstriteGolem
import co.voik.agesandtheart.content.Hadalfish
// `object` is a Kotlin keyword and Fabric put one in the package path, so it needs quoting.
import net.fabricmc.fabric.api.`object`.builder.v1.entity.FabricDefaultAttributeRegistry
import co.voik.agesandtheart.platform.FabricInkFluids
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.loot.v3.LootTableEvents
// `object` is a Kotlin keyword, and Fabric's package is spelled with one.
import net.fabricmc.fabric.api.`object`.builder.v1.world.poi.PoiHelper
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
    // The first mob the pack has, and a mob is the one kind of entity that needs its attributes declared
    // separately from its type — without this it has no health and the game refuses to spawn it.
    FabricDefaultAttributeRegistry.register(AgeContent.ASTRITE_GOLEM, AstriteGolem.createAttributes())
    FabricDefaultAttributeRegistry.register(AgeContent.HADALFISH, Hadalfish.createAttributes())
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

    // The payload type, registered here rather than in the client entrypoint: Fabric requires it on *both*
    // sides, and registering twice throws. Common init is the only place that is true of.
    PayloadTypeRegistry.clientboundPlay().register(LexiconPayload.TYPE, LexiconPayload.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(BlizzardPayload.TYPE, BlizzardPayload.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(LearnedWordsPayload.TYPE, LearnedWordsPayload.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(DeskSyncPayload.TYPE, DeskSyncPayload.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(DeskPricePayload.TYPE, DeskPricePayload.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(DeskNoticePayload.TYPE, DeskNoticePayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(DeskCommandPayload.TYPE, DeskCommandPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(LinkRequest.TYPE, LinkRequest.STREAM_CODEC)
    // The linking panel (design 7.8.1). Two clientbound, two serverbound, and the chunk payload is the
    // only one in the mod keyed to a registry buffer -- it carries vanilla's own chunk and light data.
    PayloadTypeRegistry.clientboundPlay().register(PanelLevelPayload.TYPE, PanelLevelPayload.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(PanelChunkPayload.TYPE, PanelChunkPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(PanelOpenRequest.TYPE, PanelOpenRequest.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(PanelCloseRequest.TYPE, PanelCloseRequest.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(PanelChunksWanted.TYPE, PanelChunksWanted.STREAM_CODEC)

    ServerPlayNetworking.registerGlobalReceiver(LinkRequest.TYPE) { payload, context ->
        context.server().execute { Linking.handle(context.player(), payload) }
    }

    // The desk's instructions arrive here; every one of them is re-checked server-side.
    ServerPlayNetworking.registerGlobalReceiver(DeskCommandPayload.TYPE) { payload, context ->
        context.server().execute { DeskCommands.handle(context.player(), payload) }
    }

    ServerPlayNetworking.registerGlobalReceiver(PanelOpenRequest.TYPE) { payload, context ->
        context.server().execute { PanelViews.open(context.server(), context.player(), payload.book) }
    }
    ServerPlayNetworking.registerGlobalReceiver(PanelCloseRequest.TYPE) { _, context ->
        context.server().execute { PanelViews.close(context.server(), context.player()) }
    }
    ServerPlayNetworking.registerGlobalReceiver(PanelChunksWanted.TYPE) { payload, context ->
        context.server().execute { PanelViews.resend(context.server(), context.player(), payload.positions) }
    }
    // A client that crashes with a book open never sends the close, so the ring is released here too.
    ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
        PanelViews.forget(server, handler.player)
    }

    // Nothing here about skies: telling a joining client what each level looks like is Ephemeris' own
    // bookkeeping, and it does it from its own entrypoint.
    ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
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

    // Re-open persisted Ages once the server has started (nothing auto-restores a runtime level).
    ServerLifecycleEvents.SERVER_STARTED.register { server ->
        Ages.reloadSaved(server)
    }

    // Whatever befalls an Age. A tick has no shared entry point, so both loaders call the same one.
    ServerTickEvents.END_SERVER_TICK.register(Happenings::tick)

    // And what a deretheni suit keeps off its wearer, which is the half of that no attribute can reach.
    ServerTickEvents.END_SERVER_TICK.register(ProtectiveSuit::tick)

    // And every charged machine anybody is standing near — every level, not only the Ages, since crystal
    // carried home through a book has to work where it is set down.
    ServerTickEvents.END_SERVER_TICK.register(ChargedMetal::stir)

    // And the linking panel's own beat: an open the pace was holding, and a lectern's panel its viewer has left.
    ServerTickEvents.END_SERVER_TICK.register(PanelViews::tick)

    // Where the wounds are. A wound carries no block entity, so the index is filled by reading each chunk
    // as it loads — see `Wounds`, which dismisses a section off its palette before touching a block.
    // And which of them owe the Age some tearing: a chunk that was away while the Age went on coming
    // apart is brought up to date on the tick, never here — see `Worsening.chunkArrived`.
    ServerChunkEvents.CHUNK_LOAD.register { level, chunk, _ ->
        Wounds.stocked(level, chunk)
        Worsening.chunkArrived(level, chunk.pos)
    }
    ServerChunkEvents.CHUNK_UNLOAD.register { level, chunk ->
        Wounds.emptied(level, chunk.pos)
        Worsening.chunkLeft(level, chunk.pos)
    }

    // Last, and it has to be: everything of ours is registered by now, which is the whole condition.
    CommonSetup.afterContentRegistered()
}

package co.voik.agesandtheart

import co.voik.agesandtheart.content.ChargedMetal
import co.voik.agesandtheart.content.ProtectiveSuit
import net.neoforged.neoforge.event.tick.ServerTickEvent
import co.voik.agesandtheart.age.phenomena.Happenings
import co.voik.agesandtheart.age.AgeCommand
import co.voik.agesandtheart.age.Ages
import co.voik.agesandtheart.age.word.LearnedWordsPayload
import co.voik.agesandtheart.age.phenomena.BlizzardPayload
import co.voik.agesandtheart.age.word.LexiconPayload
import co.voik.agesandtheart.age.word.PageLearning
import co.voik.agesandtheart.client.KnownWords
import co.voik.agesandtheart.desk.DeskCommandPayload
import co.voik.agesandtheart.desk.DeskCommands
import co.voik.agesandtheart.book.LinkRequest
import co.voik.agesandtheart.book.panel.PanelChunkPayload
import co.voik.agesandtheart.book.panel.PanelChunksWanted
import co.voik.agesandtheart.book.panel.PanelCloseRequest
import co.voik.agesandtheart.book.panel.PanelLevelPayload
import co.voik.agesandtheart.book.panel.PanelOpenRequest
import co.voik.agesandtheart.book.panel.PanelViews
import co.voik.agesandtheart.book.Linking
import co.voik.agesandtheart.desk.DeskNoticePayload
import co.voik.agesandtheart.desk.DeskPricePayload
import co.voik.agesandtheart.desk.DeskSyncPayload
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.AstriteGolem
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent
import co.voik.agesandtheart.platform.NeoForgeInkFluids
import net.minecraft.core.registries.Registries
import co.voik.agesandtheart.age.consequence.Worsening
import co.voik.agesandtheart.age.consequence.Wounds
import net.minecraft.world.level.Level
import net.neoforged.neoforge.event.level.ChunkEvent
import net.neoforged.bus.api.IEventBus
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.ModContainer
import net.neoforged.fml.config.ModConfig
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.registries.RegisterEvent

@Mod(Constants.MOD_ID)
class AgesAndTheArt(eventBus: IEventBus, modContainer: ModContainer) {
    init {
        CommonSetup.init()

        // The config, and the screen that reads it. NeoForge owns both `ModConfigSpec` and a generated
        // `ConfigurationScreen`, so a Config button appears on the Mods page for one line and no UI code
        // — see `notes/config-research.md` for why the spec is shaped for screens rather than for us.
        modContainer.registerConfig(ModConfig.Type.SERVER, AgeConfig.SPEC)
        modContainer.registerConfig(ModConfig.Type.CLIENT, AgeClientLook.SPEC)
        // **The screen is client-only and naming it here would take the dedicated server down** (found
        // 2026-08-09, the first time `:neoforge:runServer` could be run at all). `ConfigurationScreen`
        // extends `Screen`, which the dev dist cleaner refuses to load on a server, and the reference is
        // resolved while this constructor is being verified — so it fails during mod construction, before
        // any of our code has had a chance to check anything. Hiding it behind a lambda would not help;
        // the class has to be named somewhere else entirely, which is what [ConfigScreen] is for.
        if (FMLEnvironment.getDist() == Dist.CLIENT) ConfigScreen.offer(modContainer)

        // Content registration is a mod-bus event on NeoForge.
        eventBus.addListener(::onRegister)
        // Payload registration is a mod-bus event, so it cannot be a call from common init the way Fabric's is.
        eventBus.addListener(::onRegisterPayloads)
        eventBus.addListener(::onRegisterCapabilities)
        eventBus.addListener(::onCreateAttributes)
        // Commands are a game-bus event.
        NeoForge.EVENT_BUS.addListener(::onRegisterCommands)
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedIn)
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedOut)
        NeoForge.EVENT_BUS.addListener(::onServerStarted)
        NeoForge.EVENT_BUS.addListener(::onServerTick)
        NeoForge.EVENT_BUS.addListener(::onChunkLoad)
        NeoForge.EVENT_BUS.addListener(::onChunkUnload)
    }

    /**
     * The desk's tanks, on every part of it — a pipe touching a wing should work, since the wings are the
     * same furniture. Registered against the block rather than the block entity for exactly that reason.
     */
    private fun onRegisterCapabilities(event: net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent) {
        event.registerBlock(
            net.neoforged.neoforge.capabilities.Capabilities.Fluid.BLOCK,
            { level, pos, _, _, _ ->
                co.voik.agesandtheart.desk.WritersDeskBlock.entityAt(level, pos)
                    ?.let { co.voik.agesandtheart.platform.NeoForgeInkHandler(it) }
            },
            AgeContent.WRITERS_DESK_BLOCK,
        )
    }

    /**
     * The golem's health, reach and speed.
     *
     * A mob is the one kind of entity whose attributes are declared apart from its type, and one with none
     * is refused at spawn — so this is not optional wiring, it is the second half of registering it.
     */
    private fun onCreateAttributes(event: EntityAttributeCreationEvent) {
        event.put(AgeContent.ASTRITE_GOLEM, AstriteGolem.createAttributes().build())
    }

    private fun onRegister(event: RegisterEvent) {
        // Fluids first: a bucket names its fluid, and the pair is built together.
        NeoForgeInkFluids.register(event)

        event.register(Registries.DATA_COMPONENT_TYPE) { helper ->
            AgeContent.components.forEach { (id, comp) -> helper.register(id, comp) }
        }
        event.register(Registries.BLOCK) { helper ->
            AgeContent.blocks.forEach { (id, block) -> helper.register(id, block) }
        }
        event.register(Registries.SOUND_EVENT) { helper ->
            AgeContent.soundEvents.forEach { (id, sound) -> helper.register(id, sound) }
        }
        event.register(Registries.ITEM) { helper ->
            AgeContent.items.forEach { (id, item) -> helper.register(id, item) }
        }
        event.register(Registries.ENTITY_TYPE) { helper ->
            AgeContent.entities.forEach { (id, type) -> helper.register(id, type) }
        }
        event.register(Registries.TICKET_TYPE) { helper ->
            AgeContent.tickets.forEach { (id, type) -> helper.register(id, type) }
        }
        event.register(Registries.STRUCTURE_TYPE) { helper ->
            AgeContent.structureTypes.forEach { (id, type) -> helper.register(id, type) }
        }
        event.register(Registries.STRUCTURE_PIECE) { helper ->
            AgeContent.structurePieces.forEach { (id, type) -> helper.register(id, type) }
        }
        event.register(Registries.BLOCK_ENTITY_TYPE) { helper ->
            AgeContent.blockEntities.forEach { (id, type) -> helper.register(id, type) }
        }
        // NeoForge maps a point of interest's block states off the registry itself — see
        // NeoForgeRegistryCallbacks.PoiTypeCallbacks — so plain registration is all it takes here.
        // Fabric's half of this is `PoiHelper`, because vanilla's own state map is private.
        event.register(Registries.POINT_OF_INTEREST_TYPE) { helper ->
            AgeContent.poiTypes.forEach { (id, type) -> helper.register(id, type) }
        }
        event.register(Registries.VILLAGER_PROFESSION) { helper ->
            AgeContent.villagerProfessions.forEach { (id, profession) -> helper.register(id, profession) }
        }
        event.register(Registries.MENU) { helper ->
            AgeContent.menus.forEach { (id, type) -> helper.register(id, type) }
        }
        event.register(Registries.RECIPE_SERIALIZER) { helper ->
            AgeContent.recipeSerializers.forEach { (id, serializer) -> helper.register(id, serializer) }
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
        event.register(Registries.MATERIAL_CONDITION) { helper ->
            AgeContent.surfaceConditionCodecs.forEach { (id, codec) -> helper.register(id, codec) }
        }
        event.register(Registries.FEATURE) { helper ->
            AgeContent.features.forEach { (id, feature) -> helper.register(id, feature) }
        }
        event.register(Registries.CARVER) { helper ->
            AgeContent.carvers.forEach { (id, carver) -> helper.register(id, carver) }
        }
        event.register(Registries.LOOT_FUNCTION_TYPE) { helper ->
            AgeContent.lootFunctions.forEach { (id, fn) -> helper.register(id, fn) }
        }
    }

    /**
     * **Bump the version string whenever the payload's codec or handler semantics change**, or two modded
     * ends will negotiate a channel they disagree about.
     *
     * Nothing here about skies: Ephemeris registers its own payload from its own entrypoint.
     */
    private fun onRegisterPayloads(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar(PAYLOAD_VERSION)
        // These two land in client-only code. Registration must happen here — a clientbound payload the
        // server never registered is one it cannot send — but the handler body only runs on a client, so
        // `KnownWords` is never loaded on a dedicated server.
        registrar.playToClient(LexiconPayload.TYPE, LexiconPayload.STREAM_CODEC) { payload, _ ->
            KnownWords.remember(payload)
        }
        registrar.playToClient(BlizzardPayload.TYPE, BlizzardPayload.STREAM_CODEC) { payload, _ ->
            co.voik.agesandtheart.client.Storms.remember(payload)
        }
        registrar.playToClient(LearnedWordsPayload.TYPE, LearnedWordsPayload.STREAM_CODEC) { payload, _ ->
            KnownWords.remember(payload)
        }
        registrar.playToClient(DeskSyncPayload.TYPE, DeskSyncPayload.STREAM_CODEC) { payload, _ ->
            co.voik.agesandtheart.client.DeskModel.remember(payload)
        }
        registrar.playToClient(DeskPricePayload.TYPE, DeskPricePayload.STREAM_CODEC) { payload, _ ->
            co.voik.agesandtheart.client.DeskModel.remember(payload)
        }
        registrar.playToClient(DeskNoticePayload.TYPE, DeskNoticePayload.STREAM_CODEC) { payload, _ ->
            co.voik.agesandtheart.client.DeskModel.remember(payload)
        }
        // The desk's instructions, re-checked server-side whatever the screen believed.
        registrar.playToServer(DeskCommandPayload.TYPE, DeskCommandPayload.STREAM_CODEC) { payload, context ->
            (context.player() as? net.minecraft.server.level.ServerPlayer)?.let {
                DeskCommands.handle(it, payload)
            }
        }
        registrar.playToServer(LinkRequest.TYPE, LinkRequest.STREAM_CODEC) { payload, context ->
            (context.player() as? net.minecraft.server.level.ServerPlayer)?.let {
                Linking.handle(it, payload)
            }
        }

        // The linking panel (design 7.8.1). The chunk payload is the only one in the mod keyed to a
        // registry buffer, carrying vanilla's own chunk and light data straight through.
        registrar.playToClient(PanelLevelPayload.TYPE, PanelLevelPayload.STREAM_CODEC) { payload, _ ->
            co.voik.agesandtheart.client.panel.LinkingPanel.accept(payload)
        }
        registrar.playToClient(PanelChunkPayload.TYPE, PanelChunkPayload.STREAM_CODEC) { payload, _ ->
            co.voik.agesandtheart.client.panel.LinkingPanel.accept(payload)
        }
        registrar.playToServer(PanelOpenRequest.TYPE, PanelOpenRequest.STREAM_CODEC) { payload, context ->
            (context.player() as? net.minecraft.server.level.ServerPlayer)?.let {
                PanelViews.open(it.level().server ?: return@let, it, payload.hand)
            }
        }
        registrar.playToServer(PanelCloseRequest.TYPE, PanelCloseRequest.STREAM_CODEC) { _, context ->
            (context.player() as? net.minecraft.server.level.ServerPlayer)?.let {
                PanelViews.close(it.level().server ?: return@let, it)
            }
        }
        registrar.playToServer(PanelChunksWanted.TYPE, PanelChunksWanted.STREAM_CODEC) { payload, context ->
            (context.player() as? net.minecraft.server.level.ServerPlayer)?.let {
                PanelViews.resend(it.level().server ?: return@let, it, payload.positions)
            }
        }
    }

    private fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? net.minecraft.server.level.ServerPlayer ?: return
        PageLearning.tellEverything(player)
    }

    /**
     * Releases a linking panel's chunk ring when its viewer leaves.
     *
     * A client that crashes with a book open never sends the close, so without this the ring — and the Age
     * holding it — would stay loaded for the life of the server.
     */
    private fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? net.minecraft.server.level.ServerPlayer ?: return
        val server = player.level().server ?: return
        PanelViews.forget(server, player)
    }

    /** Re-open persisted Ages once the server has started — nothing auto-restores a runtime level. */
    private fun onServerStarted(event: ServerStartedEvent) {
        Ages.reloadSaved(event.server)
    }

    /** Whatever befalls an Age. A tick has no shared entry point, so both loaders call the same one. */
    private fun onServerTick(event: ServerTickEvent.Post) {
        Happenings.tick(event.server)
        // And what a deretheni suit keeps off its wearer, which is the half of that no attribute can reach.
        ProtectiveSuit.tick(event.server)
        // And every charged machine anybody is standing near — every level, not only the Ages, since
        // crystal carried home through a book has to work where it is set down.
        ChargedMetal.stir(event.server)
    }

    /**
     * Where the wounds are. A wound carries no block entity, so the index is filled by reading each chunk
     * as it loads — see `Wounds`, which dismisses a section off its palette before touching a block.
     *
     * One listener for both sides here, where Fabric needs a client and a server registration: `ChunkEvent`
     * fires on whichever level loaded it, and both sides want the index for different questions.
     */
    private fun onChunkLoad(event: ChunkEvent.Load) {
        val level = event.level as? Level ?: return
        Wounds.stocked(level, event.chunk)
        // And which of them owe the Age some tearing. Server levels only, which `Worsening` decides rather
        // than this listener, since the same listener answers for both sides here.
        Worsening.chunkArrived(level, event.chunk.pos)
    }

    private fun onChunkUnload(event: ChunkEvent.Unload) {
        val level = event.level as? Level ?: return
        Wounds.emptied(level, event.chunk.pos)
        Worsening.chunkLeft(level, event.chunk.pos)
    }

    private fun onRegisterCommands(event: RegisterCommandsEvent) {
        AgeCommand.register(event.dispatcher)
    }

    private companion object {
        const val PAYLOAD_VERSION = "1"
    }
}

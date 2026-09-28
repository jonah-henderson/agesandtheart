package co.voik.agesandtheart

import net.neoforged.neoforge.event.tick.ServerTickEvent
import co.voik.agesandtheart.command.AgeCommand
import co.voik.agesandtheart.generation.Ages
import co.voik.agesandtheart.generation.WorldgenCodecs
import co.voik.agesandtheart.content.AgeComponents
import co.voik.agesandtheart.content.AgeContent
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent
import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent
import co.voik.agesandtheart.platform.NeoForgeDeepWater
import co.voik.agesandtheart.platform.NeoForgeInkFluids
import net.minecraft.core.registries.Registries
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
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
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.registration.PayloadRegistrar
import net.neoforged.neoforge.registries.RegisterEvent
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.SpawnPlacementType
import net.minecraft.world.entity.SpawnPlacements
import net.minecraft.world.level.levelgen.Heightmap

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
        // And the work that can only be done once all of it exists. Common setup on NeoForge cannot do
        // this itself: registration is an event, so `init` returns long before the content is in.
        eventBus.addListener(::onCommonSetup)
        // Payload registration is a mod-bus event, so it cannot be a call from common init the way Fabric's is.
        eventBus.addListener(::onRegisterPayloads)
        eventBus.addListener(::onRegisterCapabilities)
        eventBus.addListener(::onCreateAttributes)
        eventBus.addListener(::onRegisterSpawnPlacements)
        // Commands are a game-bus event.
        NeoForge.EVENT_BUS.addListener(::onRegisterCommands)
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedIn)
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedOut)
        NeoForge.EVENT_BUS.addListener(::onServerStarted)
        NeoForge.EVENT_BUS.addListener(::onServerStopped)
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
        // NeoForge wraps only vanilla's own containers for pipes, so the stations are named here; Fabric's
        // transfer API wraps any Container block entity by itself.
        event.registerBlockEntity(
            net.neoforged.neoforge.capabilities.Capabilities.Item.BLOCK,
            AgeContent.STATION_ENTITY,
        ) { station, side -> net.neoforged.neoforge.transfer.item.WorldlyContainerWrapper(station, side) }
    }

    private fun onCreateAttributes(event: EntityAttributeCreationEvent) {
        AgeContent.mobAttributes.forEach { (type, attributes) -> event.put(type, attributes().build()) }
    }

    /**
     * Where our mobs may spawn. NeoForge owns the placement map and refuses a direct
     * `SpawnPlacements.register`, so this is the seam rather than Fabric's call — see
     * [AgeContent.placeWhereTheyBelong].
     */
    private fun onRegisterSpawnPlacements(event: RegisterSpawnPlacementsEvent) {
        AgeContent.placeWhereTheyBelong(object : AgeContent.SpawnPlacing {
            override fun <T : Mob> of(
                type: EntityType<T>,
                placement: SpawnPlacementType,
                heightmap: Heightmap.Types,
                rule: SpawnPlacements.SpawnPredicate<T>,
            ) = event.register(type, placement, heightmap, rule, RegisterSpawnPlacementsEvent.Operation.REPLACE)
        })
    }

    /** Fires after every `RegisterEvent`, which is exactly the condition [CommonSetup.afterContentRegistered] wants. */
    private fun onCommonSetup(event: net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent) {
        event.enqueueWork { CommonSetup.afterContentRegistered() }
    }

    private fun onRegister(event: RegisterEvent) {
        // Fluids first: a bucket names its fluid, and the pair is built together.
        NeoForgeInkFluids.register(event)
        // Deep water goes the same way and for the same reason: its block names a fluid, and only a loader
        // can build one — see `AgeFluids.DEEP_WATER`.
        NeoForgeDeepWater.register(event)

        event.register(Registries.DATA_COMPONENT_TYPE) { helper ->
            AgeComponents.components.forEach { (id, comp) -> helper.register(id, comp) }
        }
        event.register(Registries.BLOCK) { helper ->
            AgeContent.blocks.forEach { (id, block) -> helper.register(id, block) }
        }
        event.register(Registries.SOUND_EVENT) { helper ->
            AgeContent.soundEvents.forEach { (id, sound) -> helper.register(id, sound) }
        }
        event.register(Registries.MOB_EFFECT) { helper ->
            AgeContent.mobEffects.forEach { (id, effect) -> helper.register(id, effect) }
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
        event.register(Registries.STRUCTURE_PROCESSOR) { helper ->
            AgeContent.structureProcessors.forEach { (id, type) -> helper.register(id, type) }
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
        event.register(Registries.RECIPE_TYPE) { helper ->
            AgeContent.recipeTypes.forEach { (id, type) -> helper.register(id, type) }
        }
        event.register(Registries.RECIPE_BOOK_CATEGORY) { helper ->
            AgeContent.recipeBookCategories.forEach { (id, category) -> helper.register(id, category) }
        }
        event.register(Registries.CHUNK_GENERATOR) { helper ->
            WorldgenCodecs.chunkGeneratorCodecs.forEach { (id, codec) -> helper.register(id, codec) }
        }
        event.register(Registries.BIOME_SOURCE) { helper ->
            WorldgenCodecs.biomeSourceCodecs.forEach { (id, codec) -> helper.register(id, codec) }
        }
        // The *_TYPE registries, not MATERIAL_RULE/MATERIAL_CONDITION: 26.3 gave those names to the
        // datapack element registries, and the codecs a dispatch reads live in the type registries beside
        // them. Registering into the element registry here typechecks and then finds nothing at dispatch.
        event.register(Registries.MATERIAL_RULE_TYPE) { helper ->
            WorldgenCodecs.materialRuleCodecs.forEach { (id, codec) -> helper.register(id, codec) }
        }
        event.register(Registries.MATERIAL_CONDITION_TYPE) { helper ->
            WorldgenCodecs.materialConditionCodecs.forEach { (id, codec) -> helper.register(id, codec) }
        }
        event.register(Registries.FEATURE_TYPE) { helper ->
            AgeContent.features.forEach { (id, codec) -> helper.register(id, codec) }
        }
        event.register(Registries.CARVER_TYPE) { helper ->
            AgeContent.carvers.forEach { (id, codec) -> helper.register(id, codec) }
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
        Payloads.ROUTES.forEach { registerPayload(registrar, it) }
    }

    /**
     * Registers one payload, and for one a client sends, its handler.
     *
     * A clientbound payload is registered here without a handler, since one the server never registered is
     * one it cannot send; the client entrypoint gives it its handler on `RegisterClientPayloadHandlersEvent`,
     * so no client class is named in this one.
     */
    private fun <T : CustomPacketPayload> registerPayload(registrar: PayloadRegistrar, route: Payloads.Route<T>) {
        when (route) {
            is Payloads.Clientbound -> registrar.playToClient(route.type, route.codec)
            is Payloads.Serverbound -> registrar.playToServer(route.type, route.codec) { payload, context ->
                (context.player() as? ServerPlayer)?.let { route.handle(it, payload) }
            }
        }
    }

    private fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        CommonSetup.playerJoined(player)
    }

    private fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val server = player.level().server
        CommonSetup.playerLeft(server, player)
    }

    /** Re-open persisted Ages once the server has started — nothing auto-restores a runtime level. */
    private fun onServerStarted(event: ServerStartedEvent) {
        Ages.reloadSaved(event.server)
    }

    /** The parameter names the event to the bus, which is why it is taken and not read. */
    @Suppress("UNUSED_PARAMETER")
    private fun onServerStopped(event: ServerStoppedEvent) {
        CommonSetup.serverStopped()
    }

    private fun onServerTick(event: ServerTickEvent.Post) {
        CommonSetup.serverTick(event.server)
    }

    /**
     * Server levels only. `ChunkEvent` fires on whichever level loaded the chunk, and a client level's are
     * the client entrypoint's.
     */
    private fun onChunkLoad(event: ChunkEvent.Load) {
        val level = event.level as? ServerLevel ?: return
        CommonSetup.chunkLoaded(level, event.chunk)
    }

    private fun onChunkUnload(event: ChunkEvent.Unload) {
        val level = event.level as? ServerLevel ?: return
        CommonSetup.chunkUnloaded(level, event.chunk.pos)
    }

    private fun onRegisterCommands(event: RegisterCommandsEvent) {
        AgeCommand.register(event.dispatcher)
    }

    private companion object {
        const val PAYLOAD_VERSION = "3"
    }
}

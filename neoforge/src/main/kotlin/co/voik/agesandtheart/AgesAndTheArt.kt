package co.voik.agesandtheart

import co.voik.agesandtheart.age.AgeCommand
import co.voik.agesandtheart.age.word.LearnedWordsPayload
import co.voik.agesandtheart.age.word.LexiconPayload
import co.voik.agesandtheart.age.word.PageLearning
import co.voik.agesandtheart.client.KnownWords
import co.voik.agesandtheart.desk.DeskCommandPayload
import co.voik.agesandtheart.desk.DeskCommands
import co.voik.agesandtheart.book.LinkRequest
import co.voik.agesandtheart.book.Linking
import co.voik.agesandtheart.desk.DeskNoticePayload
import co.voik.agesandtheart.desk.DeskPricePayload
import co.voik.agesandtheart.desk.DeskSyncPayload
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.platform.NeoForgeInkFluids
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
        eventBus.addListener(::onRegisterCapabilities)
        // Commands are a game-bus event.
        NeoForge.EVENT_BUS.addListener(::onRegisterCommands)
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedIn)
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

    private fun onRegister(event: RegisterEvent) {
        // Fluids first: a bucket names its fluid, and the pair is built together.
        NeoForgeInkFluids.register(event)

        event.register(Registries.DATA_COMPONENT_TYPE) { helper ->
            AgeContent.components.forEach { (id, comp) -> helper.register(id, comp) }
        }
        event.register(Registries.BLOCK) { helper ->
            AgeContent.blocks.forEach { (id, block) -> helper.register(id, block) }
        }
        event.register(Registries.ITEM) { helper ->
            AgeContent.items.forEach { (id, item) -> helper.register(id, item) }
        }
        event.register(Registries.ENTITY_TYPE) { helper ->
            AgeContent.entities.forEach { (id, type) -> helper.register(id, type) }
        }
        event.register(Registries.BLOCK_ENTITY_TYPE) { helper ->
            AgeContent.blockEntities.forEach { (id, type) -> helper.register(id, type) }
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
     * The handler lands the spec in [KnownSkies], which is plain data in `common` with no client types, so
     * nothing here is dist-sensitive — which is why it stays on the server-side class rather than moving to
     * `AgesAndTheArtClient`.
     */
    private fun onRegisterPayloads(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar(PAYLOAD_VERSION)
        registrar.playToClient(SkyPayload.TYPE, SkyPayload.STREAM_CODEC) { payload, _ ->
            KnownSkies.remember(payload)
        }
        // These two land in client-only code. Registration must happen here — a clientbound payload the
        // server never registered is one it cannot send — but the handler body only runs on a client, so
        // `KnownWords` is never loaded on a dedicated server.
        registrar.playToClient(LexiconPayload.TYPE, LexiconPayload.STREAM_CODEC) { payload, _ ->
            KnownWords.remember(payload)
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
    }

    private fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? net.minecraft.server.level.ServerPlayer ?: return
        Skies.tellAboutEverything(player)
        PageLearning.tellEverything(player)
    }

    private fun onRegisterCommands(event: RegisterCommandsEvent) {
        AgeCommand.register(event.dispatcher)
    }

    private companion object {
        const val PAYLOAD_VERSION = "1"
    }
}

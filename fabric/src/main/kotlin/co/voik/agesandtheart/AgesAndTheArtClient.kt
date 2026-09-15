package co.voik.agesandtheart

import co.voik.agesandtheart.age.consequence.Wounds
import co.voik.agesandtheart.age.word.LearnedWordsPayload
import co.voik.agesandtheart.age.word.LexiconPayload
import co.voik.agesandtheart.client.AstriteGolemRenderer
import co.voik.agesandtheart.client.HadalfishRenderer
import co.voik.agesandtheart.client.BookEntityRenderer
import co.voik.agesandtheart.client.SandColumnRenderer
import co.voik.agesandtheart.client.ArcBoltRenderer
import co.voik.agesandtheart.client.DriftingOreRenderer
import co.voik.agesandtheart.client.MoltenLumpRenderer
import net.minecraft.client.renderer.entity.NoopRenderer
import net.fabricmc.fabric.api.client.rendering.v1.BlockColorRegistry
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry
import co.voik.agesandtheart.client.AgeLooks
import co.voik.agesandtheart.client.DeskModel
import co.voik.agesandtheart.age.phenomena.BlizzardPayload
import co.voik.agesandtheart.client.AgeTints
import co.voik.agesandtheart.client.KnownWords
import co.voik.agesandtheart.client.LureLooks
import co.voik.agesandtheart.client.light.DeepLights
import co.voik.agesandtheart.client.light.TintedLights
import co.voik.agesandtheart.client.Storms
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import co.voik.agesandtheart.client.InkCaseScreen
import co.voik.agesandtheart.client.GeologistsToolsScreen
import co.voik.agesandtheart.client.SeismographScreen
import co.voik.agesandtheart.client.SupplyBinScreen
import co.voik.agesandtheart.client.WritersDeskScreen
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.desk.DeskNoticePayload
import co.voik.agesandtheart.desk.DeskPricePayload
import co.voik.agesandtheart.desk.DeskSyncPayload
import co.voik.agesandtheart.client.StarFissureRenderer
import co.voik.agesandtheart.client.LecternBookRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.client.gui.screens.MenuScreens
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import co.voik.agesandtheart.book.panel.PanelChunkPayload
import co.voik.agesandtheart.book.panel.PanelLevelPayload
import co.voik.agesandtheart.client.panel.LecternPanels
import co.voik.agesandtheart.client.panel.LinkingPanel
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

/** Fabric client entrypoint. */
fun initClient() {
    Constants.LOG.info("Ages client init")

    // The Art's skies, clouds and air, handed to the library that owns those seams.
    AgeLooks.register()

    co.voik.agesandtheart.platform.FabricInkRendering.register()
    co.voik.agesandtheart.platform.FabricDeepWaterRendering.register()
    EntityRendererRegistry.register(AgeContent.HADALFISH, ::HadalfishRenderer)
    EntityRendererRegistry.register(AgeContent.BOOK_ENTITY, ::BookEntityRenderer)
    EntityRendererRegistry.register(AgeContent.SAND_COLUMN, ::SandColumnRenderer)
    EntityRendererRegistry.register(AgeContent.VOLCANIC_BOMB) { MoltenLumpRenderer(it, MoltenLumpRenderer.WHOLE_LUMP) }
    EntityRendererRegistry.register(AgeContent.LAVA_DROPLET) { MoltenLumpRenderer(it, MoltenLumpRenderer.GOBBET) }
    EntityRendererRegistry.register(AgeContent.METEOR) {
        MoltenLumpRenderer(it, MoltenLumpRenderer.METEOR, MoltenLumpRenderer.METEOR_ROCK, MoltenLumpRenderer.COLD_FIRE)
    }
    EntityRendererRegistry.register(AgeContent.DRIFTING_ORE) { DriftingOreRenderer(it) }
    // Vanilla's lightning, turned to point at what was bitten — see [ArcBoltRenderer].
    EntityRendererRegistry.register(AgeContent.ARC_BOLT) { ArcBoltRenderer(it) }
    EntityRendererRegistry.register(AgeContent.ASTRITE_GOLEM, ::AstriteGolemRenderer)
    // The storm is a clock standing in the sky and is drawn by the sky, not as an entity.
    EntityRendererRegistry.register(AgeContent.METEOR_STORM) { NoopRenderer(it) }
    EntityRendererRegistry.register(AgeContent.CAVE_IN) { NoopRenderer(it) }
    BlockEntityRenderers.register(AgeContent.STAR_FISSURE_ENTITY) { StarFissureRenderer() }
    // In vanilla's place, for the books of ours a lectern can hold; vanilla's own it still draws as before.
    BlockEntityRenderers.register(BlockEntityType.LECTERN) { LecternBookRenderer(it) }
    MenuScreens.register(AgeContent.WRITERS_DESK_MENU, ::WritersDeskScreen)
    MenuScreens.register(AgeContent.INK_CASE_MENU, ::InkCaseScreen)
    MenuScreens.register(AgeContent.SUPPLY_BIN_MENU, ::SupplyBinScreen)
    // Its own screen rather than a line on the desk's -- an implement that does something is the thing
    // you go and look at (Jonah, 2026-09-07).
    MenuScreens.register(AgeContent.SEISMOGRAPH_MENU, ::SeismographScreen)
    MenuScreens.register(AgeContent.GEOLOGISTS_TOOLS_MENU, ::GeologistsToolsScreen)
    // Vanilla's own container screen: a toolbox is a chest's grid with a fence on what may go in it, and
    // the fence lives in the menu rather than in the drawing.
    MenuScreens.register(AgeContent.TOOLBOX_MENU, ::ContainerScreen)
    AgeTints.register { sources, block -> BlockColorRegistry.register(sources, block) }
    ClientPlayNetworking.registerGlobalReceiver(DeskSyncPayload.TYPE) { payload, _ ->
        DeskModel.remember(payload)
    }
    ClientPlayNetworking.registerGlobalReceiver(DeskPricePayload.TYPE) { payload, _ ->
        DeskModel.remember(payload)
    }
    ClientPlayNetworking.registerGlobalReceiver(DeskNoticePayload.TYPE) { payload, _ ->
        DeskModel.remember(payload)
    }

    ClientPlayNetworking.registerGlobalReceiver(LexiconPayload.TYPE) { payload, _ ->
        KnownWords.remember(payload)
    }
    ClientPlayNetworking.registerGlobalReceiver(BlizzardPayload.TYPE) { payload, _ ->
        Storms.remember(payload)
    }
    // Which of the two winds is playing has to be re-asked as a player walks in and out of shelter, so it
    // rides the client tick rather than the payload.
    ClientTickEvents.END_CLIENT_TICK.register(Storms::heard)
    ClientTickEvents.END_CLIENT_TICK.register(Storms::blow)
    // A lure is drawn about its cluster rather than by each block, so it rides the tick as well.
    ClientTickEvents.END_CLIENT_TICK.register(LureLooks::pulse)
    // Which lectern's panel this client shows, since a lectern has no screen to tick it as a book's does.
    ClientTickEvents.END_CLIENT_TICK.register(LecternPanels::tick)
    // The linking panel's two, both of which land on the client thread the receiver already runs on.
    ClientPlayNetworking.registerGlobalReceiver(PanelLevelPayload.TYPE) { payload, _ ->
        LinkingPanel.accept(payload)
    }
    ClientPlayNetworking.registerGlobalReceiver(PanelChunkPayload.TYPE) { payload, _ ->
        LinkingPanel.accept(payload)
    }
    ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> LinkingPanel.forget() }

    ClientPlayNetworking.registerGlobalReceiver(LearnedWordsPayload.TYPE) { payload, _ ->
        KnownWords.remember(payload)
    }

    // Where the wounds are, on this side too — the corruption gradient asks many times a frame and
    // `WoundField` draws straight out of it. A wound carries no block entity, so the index is filled by
    // reading each chunk as it arrives; see `Wounds`, which dismisses a section off its palette first.
    ClientChunkEvents.CHUNK_LOAD.register { level, chunk -> Wounds.stocked(level, chunk) }
    // The coloured-light index, filled the same way and off the same palette dismissal.
    ClientChunkEvents.CHUNK_LOAD.register { level, chunk -> TintedLights.stocked(level, chunk) }
    // And what can be seen from across an abyss — the same index shape, for the same reason.
    ClientChunkEvents.CHUNK_LOAD.register { level, chunk -> DeepLights.stocked(level, chunk) }
    ClientChunkEvents.CHUNK_UNLOAD.register { level, chunk -> Wounds.emptied(level, chunk.pos) }
    ClientChunkEvents.CHUNK_UNLOAD.register { level, chunk -> TintedLights.emptied(level, chunk.pos) }
    ClientChunkEvents.CHUNK_UNLOAD.register { level, chunk -> DeepLights.emptied(level, chunk.pos) }

    // These keys mean nothing on the next server, and an Age id can be reused.
    ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
        KnownWords.forgetAll()
        DeskModel.forget()
        Wounds.forget()
        TintedLights.forget()
        DeepLights.forget()
    }
}

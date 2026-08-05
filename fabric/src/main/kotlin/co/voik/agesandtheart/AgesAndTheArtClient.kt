package co.voik.agesandtheart

import co.voik.agesandtheart.age.word.LearnedWordsPayload
import co.voik.agesandtheart.age.word.LexiconPayload
import co.voik.agesandtheart.client.BookEntityRenderer
import co.voik.agesandtheart.client.ClientDeskNetwork
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry
import co.voik.agesandtheart.client.DeskModel
import co.voik.agesandtheart.client.KnownWords
import co.voik.agesandtheart.client.InkCaseScreen
import co.voik.agesandtheart.client.SupplyBinScreen
import co.voik.agesandtheart.client.WritersDeskScreen
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.desk.DeskNoticePayload
import co.voik.agesandtheart.desk.DeskPricePayload
import co.voik.agesandtheart.desk.DeskSyncPayload
import net.minecraft.client.gui.screens.MenuScreens
import co.voik.agesandtheart.sky.KnownLooks
import co.voik.agesandtheart.sky.LookPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

/**
 * Fabric client entrypoint.
 *
 * **Nothing registers a renderer any more.** An Age's sky is drawn by the Mixin on
 * `SkyRenderer.renderSunMoonAndStars`, which reads the level itself — so there is no per-dimension
 * registration to do, and none of the lazy attaching that a runtime dimension used to force. All that is
 * left on this side is learning what each Age's sky *is*.
 */
fun initClient() {
    Constants.LOG.info("Ages client init")

    // What each Age's sky is, told to us by the server. The renderer reads `KnownLooks` every frame rather
    // than being registered per dimension, so an edited Age can change what it draws — see
    // `KnownLooks.remember`.
    ClientPlayNetworking.registerGlobalReceiver(LookPayload.TYPE) { payload, _ ->
        KnownLooks.remember(payload)
        Constants.LOG.debug("Learned {} Age skies", payload.skies.size)
    }
    co.voik.agesandtheart.platform.FabricInkRendering.register()
    EntityRendererRegistry.register(AgeContent.BOOK_ENTITY, ::BookEntityRenderer)
    MenuScreens.register(AgeContent.WRITERS_DESK_MENU, ::WritersDeskScreen)
    MenuScreens.register(AgeContent.INK_CASE_MENU, ::InkCaseScreen)
    MenuScreens.register(AgeContent.SUPPLY_BIN_MENU, ::SupplyBinScreen)
    ClientDeskNetwork.sender = { payload -> ClientPlayNetworking.send(payload) }
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
    ClientPlayNetworking.registerGlobalReceiver(LearnedWordsPayload.TYPE) { payload, _ ->
        KnownWords.remember(payload)
    }

    // These keys mean nothing on the next server, and an Age id can be reused.
    ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
        KnownLooks.forgetAll()
        KnownWords.forgetAll()
        DeskModel.forget()
    }
}

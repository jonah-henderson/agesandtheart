package co.voik.agesandtheart

import co.voik.agesandtheart.sky.KnownSkies
import co.voik.agesandtheart.sky.SkyPayload
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

    // What each Age's sky is, told to us by the server. The renderer reads `KnownSkies` every frame rather
    // than being registered per dimension, so an edited Age can change what it draws — see
    // `KnownSkies.remember`.
    ClientPlayNetworking.registerGlobalReceiver(SkyPayload.TYPE) { payload, _ ->
        KnownSkies.remember(payload)
        Constants.LOG.debug("Learned {} Age skies", payload.skies.size)
    }
    // These keys mean nothing on the next server, and an Age id can be reused.
    ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> KnownSkies.forgetAll() }
}

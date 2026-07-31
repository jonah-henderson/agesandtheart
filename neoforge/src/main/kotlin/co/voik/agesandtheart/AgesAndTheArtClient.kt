package co.voik.agesandtheart

import co.voik.agesandtheart.sky.KnownSkies
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.common.NeoForge

/**
 * NeoForge's client entrypoint: a second `@Mod` with the same id and `dist = [Dist.CLIENT]`, so nothing
 * here loads on a dedicated server.
 *
 * **The sky needs no registration on either loader now.** It is drawn by the Mixin on
 * `SkyRenderer.renderSunMoonAndStars`, which lives in `common` and is listed in both loaders' Mixin
 * configs — so what used to be this loader's startup-only `DimensionSpecialEffects` problem has simply
 * stopped existing.
 *
 * **The Spire is still Fabric-only**, NeoForge being unable to open an Age at all while Fantasy is.
 */
@Mod(value = Constants.MOD_ID, dist = [Dist.CLIENT])
class AgesAndTheArtClient(@Suppress("UNUSED_PARAMETER") eventBus: IEventBus) {
    init {
        Constants.LOG.info("Ages client init (NeoForge)")
        // Forgetting the cache is a game-bus concern: these dimension keys mean nothing on the next server,
        // and an Age id can be reused, so one world's sky could otherwise appear in another's.
        NeoForge.EVENT_BUS.addListener(::onLoggingOut)
    }

    private fun onLoggingOut(event: ClientPlayerNetworkEvent.LoggingOut) {
        KnownSkies.forgetAll()
    }
}

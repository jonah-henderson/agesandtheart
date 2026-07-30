package co.voik.agesandtheart

import co.voik.agesandtheart.age.AgeGeneration
import co.voik.agesandtheart.client.NeoForgeAgeEffects
import co.voik.agesandtheart.sky.KnownSkies
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.common.NeoForge

/**
 * NeoForge's client entrypoint: a second `@Mod` with the same id and `dist = [Dist.CLIENT]`, so nothing
 * here loads on a dedicated server.
 *
 * **Registration is startup-only on this loader**, `DimensionSpecialEffectsManager` building an
 * `ImmutableMap` inside the `Minecraft` constructor behind a single-shot guard and keying it on the
 * `effects` id. A per-Age renderer was never possible here, which is why the sky is *one* renderer reading
 * each Age's spec from [KnownSkies] per frame.
 *
 * **Only the generated-Age sky is ported** — the Spire keeps its bespoke sky on Fabric alone, NeoForge
 * being unable to open an Age at all while Fantasy is Fabric-only.
 */
@Mod(value = Constants.MOD_ID, dist = [Dist.CLIENT])
class AgesAndTheArtClient(eventBus: IEventBus) {
    init {
        Constants.LOG.info("Ages client init (NeoForge)")
        eventBus.addListener(::onRegisterDimensionEffects)
        // Forgetting the cache is a game-bus concern: these dimension keys mean nothing on the next server, and an
        // Age id can be reused, so one world's sky could otherwise appear in another's.
        NeoForge.EVENT_BUS.addListener(::onLoggingOut)
    }

    private fun onRegisterDimensionEffects(event: RegisterDimensionSpecialEffectsEvent) {
        event.register(AgeGeneration.AGE_EFFECTS, NeoForgeAgeEffects())
    }

    private fun onLoggingOut(event: ClientPlayerNetworkEvent.LoggingOut) {
        KnownSkies.forgetAll()
    }
}

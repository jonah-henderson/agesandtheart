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
 * NeoForge's client entrypoint — **the first client code this loader has ever had** (step 7 of per-Age skies).
 *
 * A second `@Mod` with the same id and `dist = [Dist.CLIENT]`, which is NeoForge's own way of saying "client only";
 * the server-side class stays untouched and nothing here is loaded on a dedicated server.
 *
 * **Registration is startup-only on this loader, and that is why the design looks the way it does.**
 * `DimensionSpecialEffectsManager` builds an `ImmutableMap` inside the `Minecraft` constructor behind a single-shot
 * guard, and it is keyed on the `effects` `ResourceLocation` — there is no per-dimension hook and no runtime
 * mutation, unlike Fabric's unfrozen `IdentityHashMap` of `ResourceKey<Level>`. So a per-Age renderer was never
 * possible here, which is exactly why the sky is *one* renderer reading each Age's spec from [KnownSkies] per frame.
 *
 * **Only the generated-Age sky is ported.** The Spire keeps its bespoke sky on Fabric alone, because NeoForge cannot
 * open an Age at all — `AgeBackend` is an `isSupported = false` stub while Fantasy is Fabric-only — so this is
 * groundwork that becomes visible the day a NeoForge backend exists.
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

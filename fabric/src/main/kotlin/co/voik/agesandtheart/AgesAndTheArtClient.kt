package co.voik.agesandtheart

import co.voik.agesandtheart.age.AgeGeneration
import co.voik.agesandtheart.sky.KnownSkies
import co.voik.agesandtheart.sky.SkyPayload
import co.voik.agesandtheart.client.AgeCloudRenderer
import co.voik.agesandtheart.client.AgeDimensionEffects
import co.voik.agesandtheart.client.AgeSkyRenderer
import co.voik.agesandtheart.client.SpireDimensionEffects
import co.voik.agesandtheart.client.SpireSkyRenderer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.DimensionRenderingRegistry
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

/**
 * Keys we have already attached the Age sky renderer to. For the log line rather than correctness —
 * Fabric's registry is `putIfAbsent`, so registering twice is a silent no-op.
 */
private val skyRegistered = HashSet<ResourceKey<Level>>()

/**
 * Fabric client entrypoint. Runtime dimensions have dynamic ids we cannot pre-register a sky renderer for, so we
 * watch for the player being in an Age (its dimension type carries our effects marker) and register lazily the
 * first time we see each one.
 */
fun initClient() {
    Constants.LOG.info("Ages client init")

    // What each Age's sky is, told to us by the server. The renderer reads `KnownSkies` every frame rather than
    // being registered per dimension, so an edited Age can change what it draws — see `KnownSkies.remember`.
    ClientPlayNetworking.registerGlobalReceiver(SkyPayload.TYPE) { payload, _ ->
        KnownSkies.remember(payload)
        Constants.LOG.debug("Learned {} Age skies", payload.skies.size)
    }
    // These keys mean nothing on the next server, and an Age id can be reused.
    ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> KnownSkies.forgetAll() }

    // **Two atmospheres under two markers, which is the 2026-07-29 split.** The Spire's storm and its cloud decks
    // were written for the one handcrafted Age; while they shared a marker with generated Ages, every generated Age
    // was permanently overcast and had its stars hidden under a deck it did not have.
    // Keyed on the **effects** ids, not the dimension-type ids. Those are different namespaces of meaning and
    // using the wrong one attaches nothing at all — see `AgeGeneration.AGE_EFFECTS`.
    DimensionRenderingRegistry.registerDimensionEffects(AgeGeneration.SPIRE_EFFECTS, SpireDimensionEffects())
    DimensionRenderingRegistry.registerDimensionEffects(AgeGeneration.AGE_EFFECTS, AgeDimensionEffects())

    ClientTickEvents.END_CLIENT_TICK.register { client ->
        val level = client.level ?: return@register
        val key = level.dimension()
        when (level.dimensionType().effectsLocation()) {
            // The easter egg: its own sky, its own clouds, and no `SkySpec` to wait for because it has none.
            AgeGeneration.SPIRE_EFFECTS -> if (skyRegistered.add(key)) {
                DimensionRenderingRegistry.registerSkyRenderer(key, SpireSkyRenderer)
                DimensionRenderingRegistry.registerCloudRenderer(key, AgeCloudRenderer)
                Constants.LOG.info("Registered the Spire's bespoke sky for {}", key.location())
            }

            // A generated Age. **Wait for the sky before taking the sky over:** Fabric's hook cancels vanilla's
            // `renderSky` outright, so registering before the spec arrives would draw a dome with no sun in it —
            // and registration is `putIfAbsent` with no removal, so we would be stuck with that. Holding off makes
            // the worst case a frame or two of vanilla's sky, which is the right worst case.
            //
            // **No cloud renderer here on purpose.** Fabric only cancels vanilla's clouds when one is registered,
            // so leaving it alone means a generated Age keeps vanilla's own — the right default until clouds are
            // authorable in their own right.
            AgeGeneration.AGE_EFFECTS -> if (KnownSkies.of(key) != null && skyRegistered.add(key)) {
                DimensionRenderingRegistry.registerSkyRenderer(key, AgeSkyRenderer)
                Constants.LOG.info("Registered the Age sky for {}", key.location())
            }
        }
    }
}

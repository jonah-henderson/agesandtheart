package co.voik.agesandtheart

import co.voik.agesandtheart.age.AgeGeneration
import co.voik.agesandtheart.client.AgeCloudRenderer
import co.voik.agesandtheart.client.AgeDimensionEffects
import co.voik.agesandtheart.client.AgeSkyRenderer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.DimensionRenderingRegistry
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

/** Keys we've already attached the Age sky renderer to (avoids re-registering every tick). */
private val skyRegistered = HashSet<ResourceKey<Level>>()

/**
 * Fabric client entrypoint. Runtime dimensions have dynamic ids we can't pre-register a sky
 * renderer for, so we watch for the player being in an Age (its dimension type carries our
 * effects marker) and register the renderer lazily the first time we see each one.
 */
fun initClient() {
    Constants.LOG.info("Ages client init")

    // Steel-grey stormy fog for every Age (keyed by our shared effects marker).
    DimensionRenderingRegistry.registerDimensionEffects(AgeGeneration.AGE_DIMENSION_TYPE, AgeDimensionEffects())

    ClientTickEvents.END_CLIENT_TICK.register { mc ->
        val level = mc.level ?: return@register
        val key = level.dimension()
        if (level.dimensionType().effectsLocation() == AgeGeneration.AGE_DIMENSION_TYPE && skyRegistered.add(key)) {
            DimensionRenderingRegistry.registerSkyRenderer(key, AgeSkyRenderer)
            DimensionRenderingRegistry.registerCloudRenderer(key, AgeCloudRenderer)
            Constants.LOG.info("Registered Age sky + cloud renderers for {}", key.location())
        }
    }
}

package co.voik.agesandtheart.client

import net.fabricmc.fabric.api.client.rendering.v1.DimensionRenderingRegistry
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext

/**
 * Fabric's hook onto [AgeSky] — the whole of the loader-specific half.
 *
 * All the drawing lives in `common` so that NeoForge draws the same sky (step 7). What is left here is unpacking
 * Fabric's `WorldRenderContext` into the arguments both loaders' hooks can supply, and two details are worth the
 * comments:
 *
 * - **`positionMatrix`, not `matrixStack`.** During 1.21.1's `renderSky` there is no `PoseStack` yet — Fabric sets
 *   `matrixStack()` from a `new PoseStack()` that executes *after* the sky — so it is null here. `positionMatrix()`
 *   is vanilla's `frustumMatrix`: camera rotation with no translation, which is what a camera-centred sky wants.
 * - **`isFoggy = false`, because Fabric cannot tell us.** Vanilla computes it in `renderLevel` from
 *   `effects().isFoggyAt(...)` and the boss-bar overlay, and the context does not carry it. NeoForge's hook is handed
 *   the real value and passes it through, so that one suppression case works there and not here.
 */
object AgeSkyRenderer : DimensionRenderingRegistry.SkyRenderer {
    override fun render(context: WorldRenderContext) {
        AgeSky.draw(
            view = context.positionMatrix(),
            level = context.world(),
            camera = context.camera(),
            partialTick = context.tickCounter().getGameTimeDeltaPartialTick(false),
            isFoggy = false,
        )
    }
}

package co.voik.agesandtheart.client

import net.fabricmc.fabric.api.client.rendering.v1.DimensionRenderingRegistry
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext

/**
 * Fabric's hook onto [AgeSky] — unpacking `WorldRenderContext` into what both loaders' hooks can supply.
 * Two details:
 *
 * - **`positionMatrix`, not `matrixStack`.** There is no `PoseStack` yet during 1.21.1's `renderSky`, so
 *   `matrixStack()` is null here. `positionMatrix()` is vanilla's `frustumMatrix` — camera rotation with
 *   no translation, which is what a camera-centred sky wants.
 * - **`isFoggy = false`, because Fabric cannot tell us**; the context does not carry it, where NeoForge's
 *   hook is handed the real value.
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

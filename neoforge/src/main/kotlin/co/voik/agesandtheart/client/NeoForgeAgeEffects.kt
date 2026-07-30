package co.voik.agesandtheart.client

import net.minecraft.client.Camera
import net.minecraft.client.multiplayer.ClientLevel
import org.joml.Matrix4f

/**
 * NeoForge's hook onto [AgeSky], which here is a method on the atmosphere rather than a separate renderer:
 * NeoForge has no per-dimension render hook at all, calling `renderSky` on the `DimensionSpecialEffects`
 * registered for the dimension type's `effects` id. That is why the design is *one* renderer dispatching
 * on `level.dimension()` — it is the only shape that ports.
 *
 * **Two asymmetries against Fabric, both load-bearing.** NeoForge calls this **before** `setupFog.run()`
 * where Fabric injects after, so this must run the fog itself and Fabric's adapter must not. And NeoForge
 * is handed **`isFoggy`**, so `SkyShapes.isSkyHidden`'s fog case is honoured here and cannot be on Fabric.
 */
class NeoForgeAgeEffects : AgeDimensionEffects() {

    /**
     * Returns true when we drew, which is how NeoForge is told to skip the rest of vanilla's `renderSky`.
     * Forwarding [AgeSky.draw]'s own answer rather than a bare `true` is deliberate: where it declines,
     * letting vanilla proceed is *correct* rather than a fallback — vanilla gets the void plane and the
     * sunrise glow right, which we do not attempt.
     */
    override fun renderSky(
        level: ClientLevel,
        ticks: Int,
        partialTick: Float,
        modelViewMatrix: Matrix4f,
        camera: Camera,
        projectionMatrix: Matrix4f,
        isFoggy: Boolean,
        setupFog: Runnable,
    ): Boolean {
        // Ours to run on this loader; Fabric's hook has already done it by the time its adapter is called.
        setupFog.run()
        return AgeSky.draw(
            view = modelViewMatrix,
            level = level,
            camera = camera,
            partialTick = partialTick,
            isFoggy = isFoggy,
        )
    }
}

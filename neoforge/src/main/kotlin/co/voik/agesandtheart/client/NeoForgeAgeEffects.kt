package co.voik.agesandtheart.client

import net.minecraft.client.Camera
import net.minecraft.client.multiplayer.ClientLevel
import org.joml.Matrix4f

/**
 * NeoForge's hook onto [AgeSky], which on this loader is a method on the atmosphere rather than a separate renderer.
 *
 * Where Fabric keys a `SkyRenderer` on `ResourceKey<Level>`, NeoForge has no per-dimension render hook at all — it
 * calls `renderSky` on the `DimensionSpecialEffects` registered for the dimension type's `effects` id. That is why
 * the design settled on *one* renderer dispatching on `level.dimension()`: it is the only shape that ports, and
 * [AgeSky] reads the Age's spec from `KnownSkies` per frame rather than being bound to a dimension.
 *
 * **Two asymmetries against Fabric, both load-bearing.**
 *
 * NeoForge calls this **before** `setupFog.run()`, where Fabric injects *after* it — so this must run the fog
 * itself or an Age gets whatever fog the previous pass left. Fabric's adapter must *not*.
 *
 * And NeoForge is handed **`isFoggy`**, which Fabric's `WorldRenderContext` does not carry. So the fifth suppression
 * case in `SkyShapes.isSkyHidden` — vanilla's "do not draw a sky in a fog volume" — is honoured here and cannot be
 * on Fabric. That is a property of Fabric's hook, not a defect in ours.
 */
class NeoForgeAgeEffects : AgeDimensionEffects() {

    /**
     * Returns true when we drew, which is how NeoForge is told to skip the rest of vanilla's `renderSky`.
     *
     * Returning [AgeSky.draw]'s own answer rather than a bare `true` is deliberate: it declines in the cases vanilla
     * declines too, and then letting vanilla proceed is the *correct* outcome rather than a fallback — vanilla will
     * also draw nothing, and it will get the void plane and the sunrise glow right, which we do not attempt.
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

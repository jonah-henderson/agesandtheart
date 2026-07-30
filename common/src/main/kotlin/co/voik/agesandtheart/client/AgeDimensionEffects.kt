package co.voik.agesandtheart.client

import net.minecraft.client.renderer.DimensionSpecialEffects
import net.minecraft.world.phys.Vec3

/**
 * Atmosphere for a **generated** Age: vanilla's, as nearly as this class can say it.
 *
 * **Deliberately unremarkable.** An Age reaches our renderer because it has *suns* vanilla cannot draw, not
 * because it wants different weather or a different haze — so everything here matches `OverworldEffects` and the
 * only departure is `SkyType.NONE`, which stops vanilla drawing a sky underneath the one [AgeSkyRenderer] draws.
 *
 * This class used to be the Spire's storm atmosphere, applied to every Age because the two shared one effects
 * marker. That is now `SpireDimensionEffects` under `agesandtheart:spire` (2026-07-29), which stays on the Fabric
 * side because the Spire's sky is Fabric-only.
 *
 * **In `common` since step 7**, so both loaders register the same atmosphere. NeoForge subclasses it to add its
 * `renderSky` override — that hook is an interface NeoForge patches onto `DimensionSpecialEffects`, so it cannot be
 * named here — while Fabric uses this directly and hangs its sky off a separate `SkyRenderer`.
 *
 * `cloudLevel` is vanilla's 192 rather than `NaN`, and that is the load-bearing line: **NaN would disable clouds
 * entirely.** A generated Age has no cloud renderer of its own registered, so leaving the height real means
 * *vanilla's* clouds draw — which is the right default until clouds become authorable in their own right.
 */
open class AgeDimensionEffects : DimensionSpecialEffects(
    VANILLA_CLOUD_LEVEL,
    true,             // hasGround
    SkyType.NONE,     // AgeSkyRenderer draws the sky; vanilla must not draw one under it
    false,            // forceBrightLightmap
    false,            // constantAmbientLight
) {
    /** `OverworldEffects`' own curve, so distance fog is indistinguishable from an ordinary world's. */
    override fun getBrightnessDependentFogColor(fogColor: Vec3, brightness: Float): Vec3 = fogColor.multiply(
        (brightness * 0.94f + 0.06f).toDouble(),
        (brightness * 0.94f + 0.06f).toDouble(),
        (brightness * 0.91f + 0.09f).toDouble(),
    )

    override fun isFoggyAt(x: Int, y: Int): Boolean = false

    private companion object {
        /** `DimensionSpecialEffects.OverworldEffects.CLOUD_LEVEL`, which is not public to borrow. */
        const val VANILLA_CLOUD_LEVEL = 192.0f
    }
}

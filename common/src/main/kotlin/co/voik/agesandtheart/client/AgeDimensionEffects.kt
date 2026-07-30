package co.voik.agesandtheart.client

import net.minecraft.client.renderer.DimensionSpecialEffects
import net.minecraft.world.phys.Vec3

/**
 * Atmosphere for a **generated** Age: vanilla's, as nearly as this class can say it.
 *
 * **Deliberately unremarkable.** An Age reaches our renderer because it has *suns* vanilla cannot draw,
 * not because it wants different weather — so everything matches `OverworldEffects` and the only departure
 * is `SkyType.NONE`, which stops vanilla drawing a sky under [AgeSky]'s.
 *
 * **In `common` so both loaders register the same atmosphere.** NeoForge subclasses it to add its
 * `renderSky` override, that hook being an interface NeoForge patches on and so unnameable here; Fabric
 * uses this directly and hangs its sky off a separate `SkyRenderer`.
 *
 * **`cloudLevel` is vanilla's 192 rather than `NaN`**, which would disable clouds entirely. A generated
 * Age registers no cloud renderer, so a real height means vanilla's clouds draw.
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

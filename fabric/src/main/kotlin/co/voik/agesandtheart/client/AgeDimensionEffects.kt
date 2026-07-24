package co.voik.agesandtheart.client

import net.minecraft.client.renderer.DimensionSpecialEffects
import net.minecraft.world.phys.Vec3

/**
 * Atmosphere for Ages: steel-grey, stormy fog. Registered against our `agesandtheart:age`
 * effects marker, so it applies to every Age. The sky dome itself is drawn by [AgeSkyRenderer];
 * this governs distance fog / horizon haze.
 */
class AgeDimensionEffects : DimensionSpecialEffects(
    Float.NaN,        // cloudLevel: NaN = no vanilla clouds (we'll draw our own layers later)
    true,             // hasGround
    SkyType.NONE,     // we render our own sky
    false,            // forceBrightLightmap
    false,            // constantAmbientLight
) {
    override fun getBrightnessDependentFogColor(fogColor: Vec3, brightness: Float): Vec3 =
        Vec3(0.18, 0.21, 0.21) // dark storm-grey, faint teal (slightly lighter than the zenith)

    override fun isFoggyAt(x: Int, y: Int): Boolean = false
}

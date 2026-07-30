package co.voik.agesandtheart.client

import net.minecraft.client.renderer.DimensionSpecialEffects
import net.minecraft.world.phys.Vec3

/**
 * The Spire's atmosphere: steel-grey stormy fog, and a cloud level at its upper deck. Registered against
 * `agesandtheart:spire`, its own marker — [AgeDimensionEffects] is the general one.
 *
 * The sky dome itself is [SpireSkyRenderer]'s; this governs distance fog and horizon haze.
 */
class SpireDimensionEffects : DimensionSpecialEffects(
    // cloudLevel: non-NaN so the cloud hook fires; AgeCloudRenderer draws the real decks at this height.
    AgeCloudRenderer.UPPER_DECK_HEIGHT.toFloat(),
    true,             // hasGround
    SkyType.NONE,     // we render our own sky
    false,            // forceBrightLightmap
    false,            // constantAmbientLight
) {
    override fun getBrightnessDependentFogColor(fogColor: Vec3, brightness: Float): Vec3 =
        Vec3(0.18, 0.21, 0.21) // dark storm-grey, faint teal (slightly lighter than the zenith)

    override fun isFoggyAt(x: Int, y: Int): Boolean = false
}

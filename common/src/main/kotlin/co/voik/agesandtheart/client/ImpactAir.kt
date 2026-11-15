package co.voik.agesandtheart.client

import co.voik.ephemeris.Rgba
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.attribute.EnvironmentAttributeSystem
import net.minecraft.world.attribute.EnvironmentAttributes
import org.joml.Vector3f
import org.joml.Vector3fc
import org.joml.Vector4f

/**
 * What an asteroid's end does to the air (design §5.3 "The asteroid"): the flash whitening everything for a moment,
 * the sky and the light going red as the debris comes back down, and then the dust — the view closed in, the sky
 * near black, the light failing — easing to a grey impact winter that never lifts.
 *
 * Read off the schedule [Impacts] was told and the level's clock, so every layer asks the same moment.
 */
object ImpactAir {

    fun paint(level: ClientLevel, layers: EnvironmentAttributeSystem.Builder): EnvironmentAttributeSystem.Builder {
        layers.addPositionalLayer(EnvironmentAttributes.SKY_COLOR) { was, _, _ ->
            coloured(level, was, heatTo = HEAT_SKY, dustTo = DUST_SKY)
        }
        layers.addPositionalLayer(EnvironmentAttributes.FOG_COLOR) { was, _, _ ->
            coloured(level, was, heatTo = HEAT_FOG, dustTo = DUST_FOG)
        }
        layers.addPositionalLayer(EnvironmentAttributes.SKY_LIGHT_COLOR) { was, _, _ ->
            coloured(level, was, heatTo = HEAT_LIGHT, dustTo = DUST_LIGHT)
        }
        layers.addPositionalLayer(EnvironmentAttributes.AMBIENT_LIGHT_COLOR) { was, _, _ ->
            coloured(level, was, heatTo = HEAT_LIGHT, dustTo = DUST_LIGHT, howMuch = AMBIENT_SHARE)
        }
        layers.addPositionalLayer(EnvironmentAttributes.CLOUD_COLOR) { was, _, _ ->
            val moment = momentIn(level) ?: return@addPositionalLayer was
            val coloured = coloured(level, Vector3f(was.x(), was.y(), was.z()), heatTo = HEAT_SKY, dustTo = DUST_SKY)
            Vector4f(coloured.x(), coloured.y(), coloured.z(), was.w() + (FULL - was.w()) * moment.dust)
        }
        layers.addPositionalLayer(EnvironmentAttributes.SKY_LIGHT_FACTOR) { was, _, _ ->
            val moment = momentIn(level) ?: return@addPositionalLayer was
            val flashed = was + (FULL - was) * moment.flash
            flashed + (DARKEST_LIGHT - flashed) * moment.dust * DUST_DARKENS_BY
        }
        layers.addPositionalLayer(EnvironmentAttributes.FOG_START_DISTANCE) { was, _, _ -> closedIn(level, was, DUST_FOG_STARTS) }
        layers.addPositionalLayer(EnvironmentAttributes.FOG_END_DISTANCE) { was, _, _ -> closedIn(level, was, DUST_FOG_ENDS) }
        layers.addPositionalLayer(EnvironmentAttributes.SKY_FOG_END_DISTANCE) { was, _, _ -> closedIn(level, was, DUST_FOG_ENDS) }
        layers.addPositionalLayer(EnvironmentAttributes.CLOUD_FOG_END_DISTANCE) { was, _, _ -> closedIn(level, was, DUST_FOG_ENDS) }
        return layers
    }

    /** How far through the end [level] is, for the layers — or null where no end is coming, the common case. */
    private fun momentIn(level: ClientLevel): Moment? {
        val doom = Impacts.comingTo(level) ?: return null
        val time = level.gameTime.toDouble()
        return Moment(Impacts.heatAt(doom, time), Impacts.dustAt(doom, time), Impacts.flashAt(doom, time))
    }

    private data class Moment(val heat: Float, val dust: Float, val flash: Float)

    private fun coloured(level: ClientLevel, was: Vector3fc, heatTo: Rgba, dustTo: Rgba, howMuch: Float = FULL): Vector3fc {
        val moment = momentIn(level) ?: return was
        val heated = Rgba.of(was).lerp(heatTo, moment.heat * howMuch)
        val dusted = heated.lerp(dustTo, moment.dust * howMuch)
        return dusted.lerp(Rgba.WHITE, moment.flash * howMuch).rgb()
    }

    private fun closedIn(level: ClientLevel, was: Float, inTheDust: Float): Float {
        val moment = momentIn(level) ?: return was
        val target = minOf(inTheDust, was)
        return was + (target - was) * moment.dust
    }

    private val HEAT_SKY = Rgba(0.85f, 0.30f, 0.08f)
    private val HEAT_FOG = Rgba(0.78f, 0.32f, 0.10f)
    private val HEAT_LIGHT = Rgba(1.0f, 0.45f, 0.20f)
    private val DUST_SKY = Rgba(0.17f, 0.15f, 0.14f)
    private val DUST_FOG = Rgba(0.24f, 0.21f, 0.19f)
    private val DUST_LIGHT = Rgba(0.55f, 0.50f, 0.46f)

    private const val FULL = 1.0f
    private const val AMBIENT_SHARE = 0.4f
    private const val DARKEST_LIGHT = 0.08f
    private const val DUST_DARKENS_BY = 0.85f
    private const val DUST_FOG_STARTS = 2.0f
    private const val DUST_FOG_ENDS = 40.0f
}

package co.voik.agesandtheart.math

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * An RGBA colour with channels in `0.0..1.0`. A small, testable value type so rendering code passes named
 * colours around instead of loose float quadruples.
 *
 * **Lives in `common` rather than beside the renderers** because a colour is now part of an Age's *data*: a
 * celestial body carries its tint through [co.voik.agesandtheart.sky.SkySpec], which is codec'd and sent to the
 * client. The `VertexConsumer` helpers that used to share this file stayed on the loader side, where the
 * rendering is.
 */
data class Rgba(val red: Float, val green: Float, val blue: Float, val alpha: Float = 1.0f) {

    /** Linear interpolation toward [other]; [amount] 0 = this, 1 = other. */
    fun lerp(other: Rgba, amount: Float): Rgba = Rgba(
        red + (other.red - red) * amount,
        green + (other.green - green) * amount,
        blue + (other.blue - blue) * amount,
        alpha + (other.alpha - alpha) * amount,
    )

    /**
     * This colour as one `0xAARRGGBB` integer — how vanilla's environment attributes hold a colour.
     *
     * Ours are floats because that is what a renderer multiplies by; packing is the boundary, not the
     * representation.
     */
    fun packed(): Int = (byteOf(alpha) shl 24) or (byteOf(red) shl 16) or (byteOf(green) shl 8) or byteOf(blue)

    private fun byteOf(channel: Float): Int = (channel.coerceIn(0.0f, 1.0f) * FULL).toInt()

    /** This colour at [factor] of its brightness, alpha untouched. */
    fun dimmed(factor: Float): Rgba = Rgba(red * factor, green * factor, blue * factor, alpha)

    companion object {
        val WHITE = Rgba(1.0f, 1.0f, 1.0f)

        private const val FULL = 255f

        /**
         * Alpha is optional and defaults to opaque, so a fully-lit colour is written as three numbers.
         */
        val CODEC: Codec<Rgba> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.FLOAT.fieldOf("red").forGetter(Rgba::red),
                Codec.FLOAT.fieldOf("green").forGetter(Rgba::green),
                Codec.FLOAT.fieldOf("blue").forGetter(Rgba::blue),
                Codec.FLOAT.optionalFieldOf("alpha", 1.0f).forGetter(Rgba::alpha),
            ).apply(instance, ::Rgba)
        }
    }
}

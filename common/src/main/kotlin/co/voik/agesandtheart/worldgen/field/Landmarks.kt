package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Rare places where a landform stands far taller than it does anywhere near: one at most in each cell [spacing]
 * blocks across, drawn with [chance], standing [boost] again its own height at its middle and easing back to the
 * ordinary over [radius]. A towering hill, a sea cliff twice its neighbours', a stretch of canyon cut twice as deep.
 */
data class Landmarks(
    val seed: Long,
    val spacing: Double,
    val radius: Double,
    val boost: Double,
    val chance: Double,
) {
    /** How many times its own height the ground stands here for its landmarks: one away from any. */
    fun factorAt(worldX: Int, worldZ: Int): Double {
        if (chance <= 0.0 || boost <= 0.0 || spacing <= 0.0) return 1.0
        val cellX = floor(worldX / spacing).toInt()
        val cellZ = floor(worldZ / spacing).toInt()
        var nearest = 0.0
        for (offsetX in -1..1) for (offsetZ in -1..1) {
            val x = cellX + offsetX
            val z = cellZ + offsetZ
            val isDrawn = cellHash(x, z, seed.toInt()) + HALF < chance
            if (!isDrawn) continue
            val pointX = (x + HALF + JITTER * cellHash(x, z, seed.toInt() xor JITTER_X_SALT)) * spacing
            val pointZ = (z + HALF + JITTER * cellHash(x, z, seed.toInt() xor JITTER_Z_SALT)) * spacing
            val distance = sqrt((worldX - pointX) * (worldX - pointX) + (worldZ - pointZ) * (worldZ - pointZ))
            nearest = maxOf(nearest, smoothstep(1.0 - distance / radius))
        }
        return 1.0 + boost * nearest
    }

    fun resized(factor: Double) = copy(spacing = spacing * factor, radius = radius * factor)

    private fun smoothstep(share: Double): Double {
        val clamped = share.coerceIn(0.0, 1.0)
        return clamped * clamped * (3.0 - 2.0 * clamped)
    }

    companion object {
        private const val HALF = 0.5

        /** How far a landmark strays from its cell's middle, against the cell. */
        private const val JITTER = 0.5
        private const val JITTER_X_SALT = 0x1A4D
        private const val JITTER_Z_SALT = 0x3A2C

        val NONE = Landmarks(seed = 0L, spacing = 0.0, radius = 0.0, boost = 0.0, chance = 0.0)

        val CODEC: Codec<Landmarks> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.LONG.fieldOf("seed").forGetter(Landmarks::seed),
                Codec.DOUBLE.fieldOf("spacing").forGetter(Landmarks::spacing),
                Codec.DOUBLE.fieldOf("radius").forGetter(Landmarks::radius),
                Codec.DOUBLE.fieldOf("boost").forGetter(Landmarks::boost),
                Codec.DOUBLE.fieldOf("chance").forGetter(Landmarks::chance),
            ).apply(instance, ::Landmarks)
        }
    }
}

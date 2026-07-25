package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A squashed sphere: wide, flattish, walkable — the "centre of gravity" of an island body. Solid
 * where the column lies inside the ellipsoid; its vertical reach shrinks toward the rim.
 */
data class Ellipsoid(
    val centerX: Int,
    val centerY: Int,
    val centerZ: Int,
    val radiusXZ: Double,
    val radiusY: Double,
) : TerrainField {
    override val kind = FieldKind.ELLIPSOID
    override val horizontalReach = originDistance(centerX, centerZ) + radiusXZ

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val horizontal = horizontalDistance(worldX, worldZ, centerX, centerZ)
        if (horizontal > radiusXZ) return Spans.EMPTY
        val fraction = horizontal / radiusXZ
        val reach = radiusY * sqrt(1.0 - fraction * fraction)
        return Spans.of(ceil(centerY - reach).toInt(), floor(centerY + reach).toInt())
    }

    override fun resized(factor: Double, pivotY: Int) = Ellipsoid(
        centerX = scaled(centerX, factor),
        centerY = scaledAbout(centerY, factor, pivotY),
        centerZ = scaled(centerZ, factor),
        radiusXZ = radiusXZ * factor,
        radiusY = radiusY * factor,
    )

    companion object {
        val CODEC: MapCodec<Ellipsoid> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.INT.fieldOf("center_x").forGetter(Ellipsoid::centerX),
                Codec.INT.fieldOf("center_y").forGetter(Ellipsoid::centerY),
                Codec.INT.fieldOf("center_z").forGetter(Ellipsoid::centerZ),
                Codec.DOUBLE.fieldOf("radius_xz").forGetter(Ellipsoid::radiusXZ),
                Codec.DOUBLE.fieldOf("radius_y").forGetter(Ellipsoid::radiusY),
            ).apply(instance, ::Ellipsoid)
        }
    }
}

/**
 * A tapering cone: full [baseRadius] at [baseY], shrinking to a point at [tipY]. Points up when
 * [tipY] is above the base (a stalagmite spire), down when below (a stalactite).
 */
data class Cone(
    val baseX: Int,
    val baseZ: Int,
    val baseRadius: Double,
    val baseY: Int,
    val tipY: Int,
) : TerrainField {
    override val kind = FieldKind.CONE
    override val horizontalReach = originDistance(baseX, baseZ) + baseRadius

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val horizontal = horizontalDistance(worldX, worldZ, baseX, baseZ)
        if (horizontal > baseRadius) return Spans.EMPTY
        val length = abs(tipY - baseY).toDouble()
        val reach = length * (1.0 - horizontal / baseRadius)
        return if (tipY >= baseY) {
            Spans.of(baseY, floor(baseY + reach).toInt())
        } else {
            Spans.of(ceil(baseY - reach).toInt(), baseY)
        }
    }

    override fun resized(factor: Double, pivotY: Int) = Cone(
        baseX = scaled(baseX, factor),
        baseZ = scaled(baseZ, factor),
        baseRadius = baseRadius * factor,
        baseY = scaledAbout(baseY, factor, pivotY),
        tipY = scaledAbout(tipY, factor, pivotY),
    )

    companion object {
        val CODEC: MapCodec<Cone> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.INT.fieldOf("base_x").forGetter(Cone::baseX),
                Codec.INT.fieldOf("base_z").forGetter(Cone::baseZ),
                Codec.DOUBLE.fieldOf("base_radius").forGetter(Cone::baseRadius),
                Codec.INT.fieldOf("base_y").forGetter(Cone::baseY),
                Codec.INT.fieldOf("tip_y").forGetter(Cone::tipY),
            ).apply(instance, ::Cone)
        }
    }
}

/**
 * A square-footprint pyramid: full width at [baseY], tapering to a point [height] blocks above.
 * Solid where the (Chebyshev-square) column lies inside the taper — the iconic instancing shape.
 */
data class Pyramid(
    val centerX: Int,
    val centerZ: Int,
    val baseY: Int,
    val height: Int,
    val baseHalfWidth: Int,
) : TerrainField {
    override val kind = FieldKind.PYRAMID

    // Square footprint: the farthest solid point is a base corner, baseHalfWidth·√2 from centre.
    override val horizontalReach = originDistance(centerX, centerZ) + baseHalfWidth * SQRT_TWO

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val offsetToEdge = maxOf(abs(worldX - centerX), abs(worldZ - centerZ))
        if (offsetToEdge > baseHalfWidth) return Spans.EMPTY
        // Integer taper — exact. A float version stepped unevenly on non-power-of-two sizes (floor jitter).
        val reachAbove = height * (baseHalfWidth - offsetToEdge) / baseHalfWidth
        return Spans.of(baseY, baseY + reachAbove)
    }

    // Resizing the taper's own integers is the whole point: a bigger pyramid gets more courses, each
    // still exactly one block, where stretching a built one would give uneven two-block steps.
    override fun resized(factor: Double, pivotY: Int) = Pyramid(
        centerX = scaled(centerX, factor),
        centerZ = scaled(centerZ, factor),
        baseY = scaledAbout(baseY, factor, pivotY),
        height = scaled(height, factor),
        // The taper divides by this, so it can never round down to zero.
        baseHalfWidth = scaled(baseHalfWidth, factor).coerceAtLeast(1),
    )

    companion object {
        private val SQRT_TWO = sqrt(2.0)

        val CODEC: MapCodec<Pyramid> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.INT.fieldOf("center_x").forGetter(Pyramid::centerX),
                Codec.INT.fieldOf("center_z").forGetter(Pyramid::centerZ),
                Codec.INT.fieldOf("base_y").forGetter(Pyramid::baseY),
                Codec.INT.fieldOf("height").forGetter(Pyramid::height),
                Codec.INT.fieldOf("base_half_width").forGetter(Pyramid::baseHalfWidth),
            ).apply(instance, ::Pyramid)
        }
    }
}

/**
 * A flat slab solid everywhere between [lowY] and [highY] — a ground plane, ceiling, or stratum.
 * Globally solid horizontally, so it is never an instancing template ([horizontalReach] is infinite).
 */
data class Slab(val lowY: Int, val highY: Int) : TerrainField {
    override val kind = FieldKind.SLAB
    override val horizontalReach = Double.POSITIVE_INFINITY

    override fun columnSpans(worldX: Int, worldZ: Int): Spans = Spans.of(lowY, highY)

    override fun resized(factor: Double, pivotY: Int) =
        Slab(lowY = scaledAbout(lowY, factor, pivotY), highY = scaledAbout(highY, factor, pivotY))

    companion object {
        val CODEC: MapCodec<Slab> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.INT.fieldOf("low_y").forGetter(Slab::lowY),
                Codec.INT.fieldOf("high_y").forGetter(Slab::highY),
            ).apply(instance, ::Slab)
        }
    }
}

private fun horizontalDistance(x1: Int, z1: Int, x2: Int, z2: Int): Double {
    val deltaX = (x1 - x2).toDouble()
    val deltaZ = (z1 - z2).toDouble()
    return sqrt(deltaX * deltaX + deltaZ * deltaZ)
}

/** Horizontal distance of a point from the local origin (0, 0). */
private fun originDistance(x: Int, z: Int): Double = sqrt((x * x + z * z).toDouble())

/** A length or an offset from the local origin, resized. */
internal fun scaled(value: Int, factor: Double): Int = (value * factor).roundToInt()

/** A height, resized about the [pivotY] plane — which itself stays exactly where it is. */
internal fun scaledAbout(y: Int, factor: Double, pivotY: Int): Int = pivotY + ((y - pivotY) * factor).roundToInt()

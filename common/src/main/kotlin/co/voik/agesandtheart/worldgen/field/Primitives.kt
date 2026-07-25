package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.Direction
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import net.minecraft.world.level.levelgen.synth.NormalNoise
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

/**
 * Everything on one side of an infinite plane: solid where `normal · position >= distance`. The
 * toolkit's most primitive primitive — on its own it is a ground plane or a tilted shear, but its real
 * job is as a building block.
 *
 * **Convex polyhedra are an [Intersect] of these**, which is why there is no polyhedron primitive: a
 * wedge is a box cut by one plane, an octahedron is eight planes, a cut gem is however many you like.
 * A plane through a column fixes a single height, so the span stays analytic and exact.
 *
 * Solid everywhere horizontally, so like [Slab] it is never an instancing template.
 */
data class HalfSpace(
    val normalX: Double,
    val normalY: Double,
    val normalZ: Double,
    val distance: Double,
) : TerrainField {
    override val kind = FieldKind.HALF_SPACE
    override val horizontalReach = Double.POSITIVE_INFINITY

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        // What the vertical part of the normal has to account for, once x and z have had their say.
        val remaining = distance - normalX * worldX - normalZ * worldZ
        return when {
            normalY > 0.0 -> Spans.of(boundedY(ceil(remaining / normalY)), Spans.HIGHEST_Y)
            normalY < 0.0 -> Spans.of(Spans.LOWEST_Y, boundedY(floor(remaining / normalY)))
            // A vertical face: the column is wholly on one side of it or the other.
            remaining <= 0.0 -> Spans.EVERYWHERE
            else -> Spans.EMPTY
        }
    }

    // Saturates rather than overflowing when the plane is near-horizontal and the division blows up.
    private fun boundedY(y: Double): Int = y.toInt().coerceIn(Spans.LOWEST_Y, Spans.HIGHEST_Y)

    override fun resized(factor: Double, pivotY: Int) =
        // Scaling moves the plane's offset with it, except along Y, where the pivot plane holds still.
        copy(distance = distance * factor - normalY * pivotY * (factor - 1.0))

    companion object {
        val CODEC: MapCodec<HalfSpace> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.DOUBLE.fieldOf("normal_x").forGetter(HalfSpace::normalX),
                Codec.DOUBLE.fieldOf("normal_y").forGetter(HalfSpace::normalY),
                Codec.DOUBLE.fieldOf("normal_z").forGetter(HalfSpace::normalZ),
                Codec.DOUBLE.fieldOf("distance").forGetter(HalfSpace::distance),
            ).apply(instance, ::HalfSpace)
        }
    }
}

/**
 * A circular shaft of length `2 · halfLength` running along [axis], centred on ([centerX], [centerY],
 * [centerZ]). Standing on [Direction.Axis.Y] it is a pillar, tower or mesa core; **lying on [X][
 * Direction.Axis.X] or [Z][Direction.Axis.Z] it is a tunnel or the opening of an arch.**
 *
 * A lying cylinder has to be its own shape rather than a turned upright one: the field contract answers
 * *vertical* extents for a column, so a quarter-turn about a horizontal axis would need the child's
 * horizontal extent at a given height — a question `columnSpans` cannot ask. (Turning about Y is fine,
 * which is why instancing can do yaw and not pitch.) Each orientation is analytic in its own right,
 * so nothing is lost by naming them.
 */
data class Cylinder(
    val axis: Direction.Axis,
    val centerX: Int,
    val centerY: Int,
    val centerZ: Int,
    val radius: Double,
    val halfLength: Double,
) : TerrainField {
    override val kind = FieldKind.CYLINDER

    override val horizontalReach = originDistance(centerX, centerZ) + when (axis) {
        Direction.Axis.Y -> radius
        // Lying down, the footprint is a rectangle: half its diagonal is the reach past the centre.
        Direction.Axis.X, Direction.Axis.Z -> sqrt(halfLength * halfLength + radius * radius)
    }

    override fun columnSpans(worldX: Int, worldZ: Int): Spans = when (axis) {
        // Upright: a circle in the horizontal plane, the same vertical run everywhere inside it.
        Direction.Axis.Y ->
            if (horizontalDistance(worldX, worldZ, centerX, centerZ) > radius) {
                Spans.EMPTY
            } else {
                Spans.of(ceil(centerY - halfLength).toInt(), floor(centerY + halfLength).toInt())
            }
        // Lying down: within the shaft's length, the column cuts a chord of the circular cross-section.
        Direction.Axis.X -> lyingSpans(along = worldX - centerX, across = worldZ - centerZ)
        Direction.Axis.Z -> lyingSpans(along = worldZ - centerZ, across = worldX - centerX)
    }

    private fun lyingSpans(along: Int, across: Int): Spans {
        if (abs(along) > halfLength || abs(across) > radius) return Spans.EMPTY
        val reach = sqrt(radius * radius - across.toDouble() * across)
        return Spans.of(ceil(centerY - reach).toInt(), floor(centerY + reach).toInt())
    }

    override fun resized(factor: Double, pivotY: Int) = copy(
        centerX = scaled(centerX, factor),
        centerY = scaledAbout(centerY, factor, pivotY),
        centerZ = scaled(centerZ, factor),
        radius = radius * factor,
        halfLength = halfLength * factor,
    )

    companion object {
        val CODEC: MapCodec<Cylinder> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Direction.Axis.CODEC.fieldOf("axis").forGetter(Cylinder::axis),
                Codec.INT.fieldOf("center_x").forGetter(Cylinder::centerX),
                Codec.INT.fieldOf("center_y").forGetter(Cylinder::centerY),
                Codec.INT.fieldOf("center_z").forGetter(Cylinder::centerZ),
                Codec.DOUBLE.fieldOf("radius").forGetter(Cylinder::radius),
                Codec.DOUBLE.fieldOf("half_length").forGetter(Cylinder::halfLength),
            ).apply(instance, ::Cylinder)
        }
    }
}

/** An axis-aligned cuboid — walls, plinths, monoliths, and the stock to carve other shapes from. */
data class Box(
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int,
) : TerrainField {
    override val kind = FieldKind.BOX

    // The farthest solid point is whichever corner sits furthest from the local origin.
    override val horizontalReach =
        originDistance(maxOf(abs(minX), abs(maxX)), maxOf(abs(minZ), abs(maxZ)))

    override fun columnSpans(worldX: Int, worldZ: Int): Spans =
        if (worldX < minX || worldX > maxX || worldZ < minZ || worldZ > maxZ) Spans.EMPTY else Spans.of(minY, maxY)

    override fun resized(factor: Double, pivotY: Int) = Box(
        minX = scaled(minX, factor),
        minY = scaledAbout(minY, factor, pivotY),
        minZ = scaled(minZ, factor),
        maxX = scaled(maxX, factor),
        maxY = scaledAbout(maxY, factor, pivotY),
        maxZ = scaled(maxZ, factor),
    )

    companion object {
        val CODEC: MapCodec<Box> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.INT.fieldOf("min_x").forGetter(Box::minX),
                Codec.INT.fieldOf("min_y").forGetter(Box::minY),
                Codec.INT.fieldOf("min_z").forGetter(Box::minZ),
                Codec.INT.fieldOf("max_x").forGetter(Box::maxX),
                Codec.INT.fieldOf("max_y").forGetter(Box::maxY),
                Codec.INT.fieldOf("max_z").forGetter(Box::maxZ),
            ).apply(instance, ::Box)
        }
    }
}

/**
 * A smooth, organic surface: sampled noise mapped to a surface height, solid from [floorY] up to it.
 * The toolkit's answer to rolling hills and dunes — the shapes CSG solids can't express.
 *
 * The noise is **Minecraft's own [NormalNoise]**, not a reimplementation, so [firstOctave] and
 * [amplitudes] are vanilla's own vocabulary: octave wavelengths start at `2^-firstOctave` blocks and
 * halve from there, each weighted by its amplitude (so `-7` with three amplitudes gives detail at
 * roughly 128/64/32 blocks). [horizontalScale] then stretches the whole pattern, and [relief] is how
 * far the surface swings either side of [baseY].
 *
 * Being a *heightmap*, a column is solid in one run: this can make hills, but never an overhang or a
 * floating arch — those need 3D noise, which cannot be a single span. See `notes/terrain-architecture.md`.
 *
 * Solid everywhere horizontally, so like [Slab] it is never an instancing template.
 */
data class NoiseHeightmap(
    val seed: Long,
    val firstOctave: Int,
    val amplitudes: List<Double>,
    val horizontalScale: Double,
    val baseY: Int,
    val relief: Double,
    val floorY: Int,
) : TerrainField {
    override val kind = FieldKind.NOISE_HEIGHTMAP
    override val horizontalReach = Double.POSITIVE_INFINITY

    // Vanilla forbids more amplitudes than the first octave leaves room for, and an empty list would
    // leave the noise with nothing to sum. Both can arrive from a serialised tree, so settle them here.
    private val weights = amplitudes.take(-firstOctave + 1).ifEmpty { listOf(1.0) }

    // Built once and only read afterwards (all its state is written in its own constructor), so it is
    // safe to share across the chunk workers sampling this field.
    private val noise = NormalNoise.create(XoroshiroRandomSource(seed), firstOctave, *weights.toDoubleArray())

    // A zero stretch would divide the sample coordinates to infinity.
    private val stretch = horizontalScale.coerceAtLeast(SMALLEST_STRETCH)

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val sampled = noise.getValue(worldX / stretch, 0.0, worldZ / stretch)
        return Spans.of(floorY, baseY + (sampled * relief).roundToInt())
    }

    override fun resized(factor: Double, pivotY: Int) = copy(
        horizontalScale = horizontalScale * factor,
        baseY = scaledAbout(baseY, factor, pivotY),
        relief = relief * factor,
        floorY = scaledAbout(floorY, factor, pivotY),
    )

    companion object {
        private const val SMALLEST_STRETCH = 0.01

        val CODEC: MapCodec<NoiseHeightmap> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.LONG.fieldOf("seed").forGetter(NoiseHeightmap::seed),
                Codec.INT.fieldOf("first_octave").forGetter(NoiseHeightmap::firstOctave),
                Codec.DOUBLE.listOf().fieldOf("amplitudes").forGetter(NoiseHeightmap::amplitudes),
                Codec.DOUBLE.fieldOf("horizontal_scale").forGetter(NoiseHeightmap::horizontalScale),
                Codec.INT.fieldOf("base_y").forGetter(NoiseHeightmap::baseY),
                Codec.DOUBLE.fieldOf("relief").forGetter(NoiseHeightmap::relief),
                Codec.INT.fieldOf("floor_y").forGetter(NoiseHeightmap::floorY),
            ).apply(instance, ::NoiseHeightmap)
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

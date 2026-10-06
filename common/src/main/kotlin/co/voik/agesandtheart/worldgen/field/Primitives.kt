package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.Direction
import net.minecraft.util.StringRepresentable
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
 * Everything on one side of an infinite plane: solid where `normal · position >= distance`.
 *
 * **Convex polyhedra are an [Intersect] of these**, which is why there is no polyhedron primitive. A
 * plane through a column fixes a single height, so the span stays analytic and exact.
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
 * A circular shaft of length `2 · halfLength` running along [axis]. Standing on Y it is a pillar or mesa
 * core; lying on X or Z it is a tunnel or the opening of an arch.
 *
 * **A lying cylinder is its own shape rather than a turned upright one**: the contract answers *vertical*
 * extents for a column, so a quarter-turn about a horizontal axis would need the child's horizontal extent
 * at a height — a question `columnSpans` cannot ask. (Turning about Y is fine, which is why instancing
 * does yaw and not pitch.)
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
        if (worldX !in minX..maxX || worldZ !in minZ..maxZ) Spans.EMPTY else Spans.of(minY, maxY)

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
 * A smooth, organic surface: sampled noise mapped to a height, with rock filling the gap between it and
 * the flat bound at [flatY].
 *
 * **Which way up it faces falls out of the geometry** — no flag decides it. Above [flatY] this is ground
 * rising to a rolling top; below, the same field hangs from a ceiling, which is how stalactites and the
 * undersides of floating islands are made.
 *
 * The noise is Minecraft's own [NormalNoise], so [firstOctave] and [amplitudes] are vanilla's vocabulary:
 * wavelengths start at `2^-firstOctave` blocks and halve from there, each weighted by its amplitude.
 *
 * Being a heightmap, a column is solid in one run — never an overhang or an arch, which need 3D noise.
 * Solid everywhere horizontally, so like [Slab] it is never an instancing template.
 */
data class NoiseHeightmap(
    val seed: Long,
    val firstOctave: Int,
    val amplitudes: List<Double>,
    /**
     * Blocks per unit of noise along X and Z *separately*. Equal values give isotropic lumps; stretching
     * one axis draws the relief into long parallel ridges, which is what wind-carved ground looks like —
     * yardangs are streamlined *along* the prevailing wind.
     */
    val scaleX: Double,
    val scaleZ: Double,
    /** The mean height of the noisy surface. */
    val baseY: Int,
    val relief: Double,
    /** The flat bound the rock reaches back to — a floor below [baseY], a ceiling above it. */
    val flatY: Int,
    /**
     * How the noise is bent before it becomes a height. [NoiseCharacter.RIDGED] peaks along the noise's zero
     * line, so with a negative [relief] the surface dips along long meandering channels — a river's course.
     */
    val character: NoiseCharacter = NoiseCharacter.PLAIN,
) : TerrainField {
    override val kind = FieldKind.NOISE_HEIGHTMAP
    override val horizontalReach = Double.POSITIVE_INFINITY

    // One sample per column, whatever the world's height — that is what being a heightmap buys.
    override val samplesPerColumn = 1

    // Built once and only read afterwards (all its state is written in its own constructor), so it is
    // safe to share across the chunk workers sampling this field.
    private val noise = fieldNoise(seed, firstOctave, amplitudes)

    private val stretchX = scaleX.coerceAtLeast(SMALLEST_STRETCH)
    private val stretchZ = scaleZ.coerceAtLeast(SMALLEST_STRETCH)

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val sampled = character.shape(noise.get(worldX / stretchX, 0.0, worldZ / stretchZ).toDouble())
        val surfaceY = baseY + (sampled * relief).roundToInt()
        return if (baseY >= flatY) Spans.of(flatY, surfaceY) else Spans.of(surfaceY, flatY)
    }

    override fun resized(factor: Double, pivotY: Int) = copy(
        scaleX = scaleX * factor,
        scaleZ = scaleZ * factor,
        baseY = scaledAbout(baseY, factor, pivotY),
        relief = relief * factor,
        flatY = scaledAbout(flatY, factor, pivotY),
    )

    companion object {
        val CODEC: MapCodec<NoiseHeightmap> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.LONG.fieldOf("seed").forGetter(NoiseHeightmap::seed),
                Codec.INT.fieldOf("first_octave").forGetter(NoiseHeightmap::firstOctave),
                Codec.DOUBLE.listOf().fieldOf("amplitudes").forGetter(NoiseHeightmap::amplitudes),
                Codec.DOUBLE.fieldOf("scale_x").forGetter(NoiseHeightmap::scaleX),
                Codec.DOUBLE.fieldOf("scale_z").forGetter(NoiseHeightmap::scaleZ),
                Codec.INT.fieldOf("base_y").forGetter(NoiseHeightmap::baseY),
                Codec.DOUBLE.fieldOf("relief").forGetter(NoiseHeightmap::relief),
                Codec.INT.fieldOf("flat_y").forGetter(NoiseHeightmap::flatY),
                NoiseCharacter.CODEC.optionalFieldOf("character", NoiseCharacter.PLAIN).forGetter(NoiseHeightmap::character),
            ).apply(instance, ::NoiseHeightmap)
        }
    }
}

/**
 * What shape the raw noise is bent into before it is thresholded. Each mode picks out a different part of
 * the same field, deciding whether you get isolated lumps or a connected network.
 *
 * Open by design: a new character is one `when` branch and one constant, and every existing field tree
 * keeps loading because the codec is by name.
 */
enum class NoiseCharacter : StringRepresentable {
    /** The noise as it comes: smooth lumps and hollows. Erodes a bounded shape into ribs and overhangs. */
    PLAIN {
        override fun shape(sample: Double) = sample
    },

    /**
     * `1 - 2|n|`, peaking along the noise's *zero crossings* rather than its extremes. Those crossings
     * form continuous surfaces through the volume, so thresholding near the top of the range leaves a
     * connected network of tubes and chambers — the same trick vanilla's spaghetti caves use.
     */
    RIDGED {
        override fun shape(sample: Double) = 1.0 - 2.0 * abs(sample)
    };

    /** Maps a raw noise sample onto the value the threshold is compared against. */
    abstract fun shape(sample: Double): Double

    override fun getSerializedName(): String = name.lowercase()

    companion object {
        val CODEC: Codec<NoiseCharacter> = StringRepresentable.fromEnum(NoiseCharacter::values)
    }
}

/**
 * Solid wherever 3D noise, bent by [character], rises above [threshold] — the toolkit's one genuinely
 * volumetric shape, and so the only one that can make an overhang, an arch or a cave.
 *
 * **Deliberately global and world-anchored, which is what makes it useful with [Instanced].** A template
 * is queried in local coordinates, so a noise field used *as* a template gives every copy an identical
 * form; hoisted out — `Intersect(Instanced(shapes), Noise3D)` — it reads world coordinates, so every
 * instance is cut from a different region of one field and neighbours agree where they meet.
 *
 * [lowY]/[highY] bound the walk and are the whole cost story: one sample per block between them, so a
 * band is cheap and the full world height is not. **Outside the band a column is empty**, which inside an
 * `Intersect` means nothing at all — so the band must cover whatever it is shaping.
 *
 * Unlike [NoiseHeightmap] a column is not one run. Scales are per-axis, so squashing [scaleY] draws caves
 * into wide flat chambers while stretching it gives shafts.
 *
 * **A level threshold gives sponge; a *graded* one gives ground** — see [thresholdAtTop].
 */
data class Noise3D(
    val seed: Long,
    val firstOctave: Int,
    val amplitudes: List<Double>,
    val scaleX: Double,
    val scaleY: Double,
    val scaleZ: Double,
    val character: NoiseCharacter,
    /** Solid above this. Higher is sparser; with [NoiseCharacter.RIDGED], higher is *thinner* tubes. */
    val threshold: Double,
    val lowY: Int,
    val highY: Int,
    /**
     * The threshold at [highY], where [threshold] is the one at [lowY] — or null for one level throughout,
     * which is what a cave network wants and what every field written before this had.
     *
     * **This is what turns 3D noise into a landscape rather than a sponge.** With one level the same
     * fraction of every height is solid, so the result has no up: rock and air are equally likely at the
     * bedrock and at the cloud line, and it reads as foam. Grading the level from *almost everything
     * solid* at the bottom to *almost nothing* at the top makes the same noise describe a surface — one
     * that still answers per voxel, so it keeps the overhangs, arches and stacks a heightmap cannot say.
     * It is the same thing vanilla's terrain does by adding a height-dependent offset to its density.
     *
     * The two levels are the whole silhouette: how far apart they are decides how much vertical relief
     * there is, and where they sit in the noise's range decides how much of the band is ground at all.
     */
    val thresholdAtTop: Double? = null,
) : TerrainField {
    override val kind = FieldKind.NOISE_3D
    override val horizontalReach = Double.POSITIVE_INFINITY

    // One per block through the band. The reason combinators bother to order their children.
    override val samplesPerColumn = (highY - lowY + 1).coerceAtLeast(0)

    // Written entirely in its own constructor and only read afterwards, so it is safe to share across
    // the chunk workers sampling this field.
    private val noise = fieldNoise(seed, firstOctave, amplitudes)

    private val stretchX = scaleX.coerceAtLeast(SMALLEST_STRETCH)
    private val stretchY = scaleY.coerceAtLeast(SMALLEST_STRETCH)
    private val stretchZ = scaleZ.coerceAtLeast(SMALLEST_STRETCH)

    // The threshold at each level of the band, so the walk below compares rather than interpolates. One
    // shape of loop whether or not the field is graded, and a band is a few hundred doubles at worst.
    private val thresholds: DoubleArray = run {
        val levels = (highY - lowY + 1).coerceAtLeast(0)
        val top = thresholdAtTop ?: threshold
        DoubleArray(levels) { level ->
            if (levels == 1) threshold else threshold + (top - threshold) * level / (levels - 1)
        }
    }

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        if (highY < lowY) return Spans.EMPTY
        val sampleX = worldX / stretchX
        val sampleZ = worldZ / stretchZ
        // Walked as runs rather than block by block into a list: a column is usually a handful of
        // intervals, and building it in order means Spans needs no normalising pass afterwards.
        val solid = ArrayList<IntRange>(EXPECTED_RUNS)
        var runStart: Int? = null
        for (y in lowY..highY) {
            val isSolid = character.shape(noise.get(sampleX, y / stretchY, sampleZ).toDouble()) > thresholds[y - lowY]
            if (isSolid) {
                if (runStart == null) runStart = y
            } else if (runStart != null) {
                solid += runStart..y - 1
                runStart = null
            }
        }
        runStart?.let { solid += it..highY }
        return Spans.ofAscending(solid)
    }

    override fun resized(factor: Double, pivotY: Int) = copy(
        scaleX = scaleX * factor,
        scaleY = scaleY * factor,
        scaleZ = scaleZ * factor,
        lowY = scaledAbout(lowY, factor, pivotY),
        highY = scaledAbout(highY, factor, pivotY),
    )

    companion object {
        // Enough for the usual few solid runs in a column; it grows if a column is unusually broken up.
        private const val EXPECTED_RUNS = 8

        val CODEC: MapCodec<Noise3D> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.LONG.fieldOf("seed").forGetter(Noise3D::seed),
                Codec.INT.fieldOf("first_octave").forGetter(Noise3D::firstOctave),
                Codec.DOUBLE.listOf().fieldOf("amplitudes").forGetter(Noise3D::amplitudes),
                Codec.DOUBLE.fieldOf("scale_x").forGetter(Noise3D::scaleX),
                Codec.DOUBLE.fieldOf("scale_y").forGetter(Noise3D::scaleY),
                Codec.DOUBLE.fieldOf("scale_z").forGetter(Noise3D::scaleZ),
                // Optional so a tree written before a new character existed still loads as the old look.
                NoiseCharacter.CODEC.optionalFieldOf("character", NoiseCharacter.PLAIN).forGetter(Noise3D::character),
                Codec.DOUBLE.fieldOf("threshold").forGetter(Noise3D::threshold),
                Codec.INT.fieldOf("low_y").forGetter(Noise3D::lowY),
                Codec.INT.fieldOf("high_y").forGetter(Noise3D::highY),
                // Absent for an ungraded field, which is every cave network.
                Codec.DOUBLE.optionalFieldOf("threshold_at_top")
                    .forGetter { field -> java.util.Optional.ofNullable(field.thresholdAtTop) },
            ).apply(instance) { seed, octave, amplitudes, scaleX, scaleY, scaleZ, character, threshold,
                                lowY, highY, atTop ->
                Noise3D(
                    seed, octave, amplitudes, scaleX, scaleY, scaleZ, character, threshold,
                    lowY, highY, atTop.orElse(null),
                )
            }
        }
    }
}

private fun horizontalDistance(x1: Int, z1: Int, x2: Int, z2: Int): Double {
    val deltaX = (x1 - x2).toDouble()
    val deltaZ = (z1 - z2).toDouble()
    return sqrt(deltaX * deltaX + deltaZ * deltaZ)
}

/** How far a shape authored away from the local origin reaches back towards it. */
internal fun originDistance(x: Int, z: Int): Double = sqrt((x * x + z * z).toDouble())

/** A length or an offset from the local origin, resized. */
internal fun scaled(value: Int, factor: Double): Int = (value * factor).roundToInt()

/** A height, resized about the [pivotY] plane — which itself stays exactly where it is. */
internal fun scaledAbout(y: Int, factor: Double, pivotY: Int): Int = pivotY + ((y - pivotY) * factor).roundToInt()

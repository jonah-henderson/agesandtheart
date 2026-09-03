package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A ring of round section, standing at whatever angle it was left at — a torus.
 *
 * **The one primitive here that answers per voxel rather than in closed form**, and the tilt is why. A
 * torus lying flat has an easy column: the vertical line meets it in at most two intervals and both fall
 * out of one square root. Tilt it and the same question is a quartic in y, so the honest implementation is
 * the toolkit's third tier (`terrain-architecture.md` §"The evaluation contract") — walk the column's own
 * bounded height and test each block. That is the tier already measured affordable for `Noise3D`, and this
 * is bounded where noise is not: only the columns within [horizontalReach] are walked at all, and each
 * walks at most the ring's own diameter.
 *
 * **The angles live here rather than in [Variation]** because `Variation` turns a shape by rotating the
 * *query column* into the template's frame, which is a rotation about Y and cannot be anything else. A
 * ring that only ever lay flat would need no primitive of its own — that is `Subtract` of two cylinders.
 */
data class Torus(
    val centerX: Int,
    val centerY: Int,
    val centerZ: Int,
    /** Centre of the ring to centre of its tube. */
    val ringRadius: Double,
    /** The tube's own thickness. */
    val tubeRadius: Double,
    /** How far the ring is tipped out of the horizontal, in degrees — 0 lies flat, 90 stands on edge. */
    val tilt: Double,
    /** Which way the tipped ring faces, in degrees. */
    val turn: Double,
) : TerrainField {
    override val kind = FieldKind.TORUS

    private val furthest = ringRadius + tubeRadius

    override val horizontalReach = originDistance(centerX, centerZ) + furthest

    /** Walked rather than solved, so it is dear by this tree's standards and says so. */
    override val samplesPerColumn = ceil(2 * furthest).toInt()

    // The inverse rotation, held once: a query is un-turned and then un-tipped into the ring's own frame.
    private val tiltCosine = cos(Math.toRadians(tilt))
    private val tiltSine = sin(Math.toRadians(tilt))
    private val turnCosine = cos(Math.toRadians(turn))
    private val turnSine = sin(Math.toRadians(turn))

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val offsetX = (worldX - centerX).toDouble()
        val offsetZ = (worldZ - centerZ).toDouble()
        // Cheap rejection first: nothing outside the ring's own footprint can be in it at any height.
        if (sqrt(offsetX * offsetX + offsetZ * offsetZ) > furthest) return Spans.EMPTY

        val lowest = ceil(centerY - furthest).toInt()
        val highest = floor(centerY + furthest).toInt()
        // **Up to four intervals, not two.** A vertical line through a tipped ring can pass through the
        // near side of the tube and the far side, and clip each of them twice.
        var solid = Spans.EMPTY
        var runFrom: Int? = null
        for (height in lowest..highest) {
            val inside = holds(offsetX, (height - centerY).toDouble(), offsetZ)
            if (inside && runFrom == null) runFrom = height
            if (!inside && runFrom != null) {
                solid = solid.union(Spans.of(runFrom, height - 1))
                runFrom = null
            }
        }
        runFrom?.let { solid = solid.union(Spans.of(it, highest)) }
        return solid
    }

    /**
     * Whether an offset from the ring's centre is inside its tube.
     *
     * The point is turned back into the ring's own frame, where the ring lies in the XZ plane about the Y
     * axis and the test is the ordinary one: how far the point is from the circle of [ringRadius].
     */
    private fun holds(offsetX: Double, offsetY: Double, offsetZ: Double): Boolean {
        // Un-turn about Y.
        val unturnedX = offsetX * turnCosine + offsetZ * turnSine
        val unturnedZ = offsetZ * turnCosine - offsetX * turnSine
        // Un-tip about X, which is what the turn was applied on top of.
        val flatY = offsetY * tiltCosine - unturnedZ * tiltSine
        val flatZ = unturnedZ * tiltCosine + offsetY * tiltSine

        val fromAxis = sqrt(unturnedX * unturnedX + flatZ * flatZ) - ringRadius
        return fromAxis * fromAxis + flatY * flatY <= tubeRadius * tubeRadius
    }

    override fun resized(factor: Double, pivotY: Int) = copy(
        centerX = scaled(centerX, factor),
        centerY = scaledAbout(centerY, factor, pivotY),
        centerZ = scaled(centerZ, factor),
        ringRadius = ringRadius * factor,
        tubeRadius = tubeRadius * factor,
    )

    companion object {
        val CODEC: MapCodec<Torus> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.INT.fieldOf("center_x").forGetter(Torus::centerX),
                Codec.INT.fieldOf("center_y").forGetter(Torus::centerY),
                Codec.INT.fieldOf("center_z").forGetter(Torus::centerZ),
                Codec.DOUBLE.fieldOf("ring_radius").forGetter(Torus::ringRadius),
                Codec.DOUBLE.fieldOf("tube_radius").forGetter(Torus::tubeRadius),
                Codec.DOUBLE.optionalFieldOf("tilt", LYING_FLAT).forGetter(Torus::tilt),
                Codec.DOUBLE.optionalFieldOf("turn", LYING_FLAT).forGetter(Torus::turn),
            ).apply(instance, ::Torus)
        }

        private const val LYING_FLAT = 0.0
    }
}

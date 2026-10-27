package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * [base] with scree piled at the foot of its steep faces: an apron climbing [share] of a face's height, up to
 * [deepest] blocks, and falling away from it at the angle loose rock rests at.
 *
 * Worked out on a grid [LATTICE] blocks apart, each point looking for faces over the ground around it, and blended
 * between the four points around a column. A pile never rises over the ground it lies against, only fills its
 * foot, and lies only on columns standing at or over [floorY], so a shore keeps its waterline.
 */
data class Scree(
    val base: TerrainField,
    val seed: Long,
    /** What share of a face's height the scree at its foot climbs. */
    val share: Double,
    /** The most scree piles against any face, in blocks. */
    val deepest: Double,
    /** The lowest column scree lies on, or [Spans.LOWEST_Y] for every one. */
    val floorY: Int,
    /** How worn each place is; [deepest] is the ordinary amount's. */
    val wear: Wear = Wear.EVEN,
) : TerrainField {
    override val kind = FieldKind.SCREE

    override val horizontalReach = base.horizontalReach

    override val samplesPerColumn = base.samplesPerColumn + 1

    private val tops = ColumnMemo { latticeX, latticeZ -> base.columnSpans(latticeX * LATTICE, latticeZ * LATTICE) }
    private val piles = ColumnMemo(::pileAt)

    /** How many grid points out a face can stand and still pile scree on a point. */
    private val reach = ceil(deepest * wear.most / REPOSE / LATTICE).toInt() + 1

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val standing = base.columnSpans(worldX, worldZ)
        val top = standing.ranges.lastOrNull() ?: return standing
        if (top.last < floorY) return standing
        val gridX = worldX.toDouble() / LATTICE
        val gridZ = worldZ.toDouble() / LATTICE
        val lowX = floor(gridX).toInt()
        val lowZ = floor(gridZ).toInt()
        val shareX = gridX - lowX
        val shareZ = gridZ - lowZ
        var pile = 0.0
        var pileTop = 0.0
        for (offsetX in 0..1) for (offsetZ in 0..1) {
            val pointX = lowX + offsetX
            val pointZ = lowZ + offsetZ
            val weight = (if (offsetX == 0) 1.0 - shareX else shareX) * (if (offsetZ == 0) 1.0 - shareZ else shareZ)
            val pointTop = topAt(pointX, pointZ) ?: return standing
            val pointPile = piles.at(pointX, pointZ)
            pile += weight * pointPile
            pileTop += weight * (pointTop + pointPile)
        }
        // The pile's own height here, never past the blend of the grid's piled tops, which is what stops it climbing
        // a face it lies under.
        val piledTo = min(top.last + pile, pileTop).roundToInt()
        if (piledTo <= top.last) return standing
        return Spans.ofAscending(standing.ranges.dropLast(1) + listOf(top.first..piledTo))
    }

    /**
     * How deep scree lies at one grid point: the most any steep face around it piles there. Nothing piles on a point
     * that is itself partway down a face, only on one with level ground below it.
     */
    private fun pileAt(latticeX: Int, latticeZ: Int): Double {
        val here = topAt(latticeX, latticeZ) ?: return 0.0
        if (here < floorY) return 0.0
        val lowestBeside = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
            .minOf { (offsetX, offsetZ) -> topAt(latticeX + offsetX, latticeZ + offsetZ) ?: here }
        val isAtAFoot = here - lowestBeside <= LATTICE * STEEPEST_FOOT
        if (!isAtAFoot) return 0.0
        val wearHere = wear.at(latticeX * LATTICE, latticeZ * LATTICE)
        if (wearHere <= 0.0) return 0.0
        var deepestHere = 0.0
        for (offsetX in -reach..reach) for (offsetZ in -reach..reach) {
            if (offsetX == 0 && offsetZ == 0) continue
            val there = topAt(latticeX + offsetX, latticeZ + offsetZ) ?: continue
            val rise = (there - here).toDouble()
            val distance = sqrt((offsetX * offsetX + offsetZ * offsetZ).toDouble()) * LATTICE
            val isAFace = rise > distance * STEEPEST_UNPILED
            if (!isAFace) continue
            val atTheFoot = min(rise * share, deepest) * wearHere
            val fromTheFoot = max(distance - LATTICE, 0.0)
            deepestHere = max(deepestHere, atTheFoot - fromTheFoot * REPOSE)
        }
        val uneven = 1.0 + UNEVENNESS * cellHash(latticeX, latticeZ, seed.toInt())
        return deepestHere * uneven
    }

    private fun topAt(latticeX: Int, latticeZ: Int): Int? = tops.at(latticeX, latticeZ).highestSolidY

    override fun resized(factor: Double, pivotY: Int) = copy(
        base = base.resized(factor, pivotY),
        deepest = deepest * factor,
        floorY = scaledAbout(floorY, factor, pivotY),
        wear = wear.resized(factor),
    )

    companion object {
        private const val LATTICE = 8

        /** The slope loose rock rests at, about thirty-five degrees. */
        private const val REPOSE = 0.7

        /** Ground climbing no steeper than this on average, a slope rather than a face, piles nothing. */
        private const val STEEPEST_UNPILED = 1.0

        /** Ground falling away more steeply than this below a point is a face's own slope, not its foot. */
        private const val STEEPEST_FOOT = 0.5

        /** How far one point's pile stands over or under its neighbours', against its own depth. */
        private const val UNEVENNESS = 0.6

        fun codec(self: Codec<TerrainField>): MapCodec<Scree> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                self.fieldOf("base").forGetter(Scree::base),
                Codec.LONG.fieldOf("seed").forGetter(Scree::seed),
                Codec.DOUBLE.fieldOf("share").forGetter(Scree::share),
                Codec.DOUBLE.fieldOf("deepest").forGetter(Scree::deepest),
                Codec.INT.fieldOf("floor_y").forGetter(Scree::floorY),
                Wear.FIELD.forGetter(Scree::wear),
            ).apply(instance, ::Scree)
        }
    }
}

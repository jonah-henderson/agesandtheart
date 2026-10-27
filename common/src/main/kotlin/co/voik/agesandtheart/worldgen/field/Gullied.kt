package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * [base] with gullies cut down its slopes: stripes running downhill wherever the ground is steep, lowering the top
 * of each column by up to [depth] blocks — the look of ground water has worn, without simulating any water.
 *
 * The slope is read off the base's own heights on a grid [LATTICE] blocks apart, so each column costs a few
 * remembered lookups rather than a neighbour's whole column. Within a cell of [cellBlocks] the stripes take one
 * phase, and a column blends the four cells around it by their phases rather than their values, which keeps every
 * stripe at full depth instead of washing out between cells. A second, finer octave cuts smaller rills across the
 * first.
 *
 * Nothing is cut below [floorY], so a shore keeps its waterline, and only the top span of a column is touched.
 */
data class Gullied(
    val base: TerrainField,
    val seed: Long,
    /** How deep the coarser gullies run where the ground is steep, in blocks; the rills run half as deep. */
    val depth: Double,
    /** How wide a cell of one phase is, in blocks; the gullies run [cellBlocks] / [STRIPES_PER_CELL] apart. */
    val cellBlocks: Double,
    /** The lowest a cut may reach, or [Spans.LOWEST_Y] for none. */
    val floorY: Int,
) : TerrainField {
    override val kind = FieldKind.GULLIED

    override val horizontalReach = base.horizontalReach

    override val samplesPerColumn = base.samplesPerColumn + 2

    private val lattice = ColumnMemo { latticeX, latticeZ -> base.columnSpans(latticeX * LATTICE, latticeZ * LATTICE) }

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val standing = base.columnSpans(worldX, worldZ)
        val top = standing.ranges.lastOrNull() ?: return standing
        val (slopeX, slopeZ) = slopeAt(worldX, worldZ) ?: return standing
        val steepness = sqrt(slopeX * slopeX + slopeZ * slopeZ)
        val worn = smoothstep((steepness - FLATTEST_WORN) / (STEEPEST_WORN - FLATTEST_WORN))
        if (worn <= 0.0) return standing
        val aboveTheFloor = (top.last - floorY).toDouble()
        val cut = (depth * worn * (gullyAt(worldX, worldZ, slopeX, slopeZ, cellBlocks, 0L) +
            RILL_DEPTH * gullyAt(worldX, worldZ, slopeX, slopeZ, cellBlocks * RILL_CELL, RILL_SALT)))
            .coerceAtMost(aboveTheFloor).roundToInt()
        if (cut <= 0) return standing
        val lowered = (top.last - cut).coerceAtLeast(top.first)
        return Spans.ofAscending(standing.ranges.dropLast(1) + listOf(top.first..lowered))
    }

    /**
     * How deep a gully stands here, from nothing on a ridge to one in a trough. Stripes run along the slope, so each
     * cell's are a cosine across it; the four cells around the column are blended as phases.
     */
    private fun gullyAt(worldX: Int, worldZ: Int, slopeX: Double, slopeZ: Double, cell: Double, salt: Long): Double {
        val downhill = atan2(slopeZ, slopeX)
        val acrossX = -sin(downhill)
        val acrossZ = cos(downhill)
        val cellX = worldX / cell
        val cellZ = worldZ / cell
        val lowX = floor(cellX).toInt()
        val lowZ = floor(cellZ).toInt()
        var real = 0.0
        var imaginary = 0.0
        for (offsetX in 0..1) for (offsetZ in 0..1) {
            val cornerX = lowX + offsetX
            val cornerZ = lowZ + offsetZ
            val weightX = smoothstep(1.0 - abs(cellX - cornerX))
            val weightZ = smoothstep(1.0 - abs(cellZ - cornerZ))
            val weight = weightX * weightZ
            val pointX = (cornerX + jitter(cornerX, cornerZ, seed xor salt)) * cell
            val pointZ = (cornerZ + jitter(cornerZ, cornerX, seed xor salt xor JITTER_SALT)) * cell
            val across = (worldX - pointX) * acrossX + (worldZ - pointZ) * acrossZ
            val phase = across / (cell / STRIPES_PER_CELL) * TURN
            real += weight * cos(phase)
            imaginary += weight * sin(phase)
        }
        val length = sqrt(real * real + imaginary * imaginary)
        if (length <= 0.0) return 0.0
        val trough = (1.0 - real / length) / 2.0
        return trough * trough
    }

    /** The slope of the base's top here, in blocks per block, blended from the four grid points around it. */
    private fun slopeAt(worldX: Int, worldZ: Int): Pair<Double, Double>? {
        val gridX = worldX.toDouble() / LATTICE
        val gridZ = worldZ.toDouble() / LATTICE
        val lowX = floor(gridX).toInt()
        val lowZ = floor(gridZ).toInt()
        val shareX = gridX - lowX
        val shareZ = gridZ - lowZ
        var slopeX = 0.0
        var slopeZ = 0.0
        for (offsetX in 0..1) for (offsetZ in 0..1) {
            val pointX = lowX + offsetX
            val pointZ = lowZ + offsetZ
            val weight = (if (offsetX == 0) 1.0 - shareX else shareX) * (if (offsetZ == 0) 1.0 - shareZ else shareZ)
            val east = topAt(pointX + 1, pointZ) ?: return null
            val west = topAt(pointX - 1, pointZ) ?: return null
            val south = topAt(pointX, pointZ + 1) ?: return null
            val north = topAt(pointX, pointZ - 1) ?: return null
            slopeX += weight * (east - west) / (2.0 * LATTICE)
            slopeZ += weight * (south - north) / (2.0 * LATTICE)
        }
        return slopeX to slopeZ
    }

    private fun topAt(latticeX: Int, latticeZ: Int): Int? = lattice.at(latticeX, latticeZ).highestSolidY

    override fun resized(factor: Double, pivotY: Int) = copy(
        base = base.resized(factor, pivotY),
        depth = depth * factor,
        cellBlocks = cellBlocks * factor,
        floorY = pivotY + ((floorY - pivotY) * factor).roundToInt(),
    )

    companion object {
        /** How far apart the heights the slope is read from stand. */
        private const val LATTICE = 8

        /** Below this slope ground is not worn at all; at [STEEPEST_WORN] it is worn in full. */
        private const val FLATTEST_WORN = 0.15
        private const val STEEPEST_WORN = 0.8

        private const val STRIPES_PER_CELL = 2.0

        /** The rills' cell against the gullies', and their depth against the gullies'. */
        private const val RILL_CELL = 0.4
        private const val RILL_DEPTH = 0.35

        private const val TURN = 2.0 * Math.PI
        private const val RILL_SALT = 0x2111_5A17L
        private const val JITTER_SALT = 0x7177_E2L

        private fun smoothstep(share: Double): Double {
            val clamped = share.coerceIn(0.0, 1.0)
            return clamped * clamped * (3.0 - 2.0 * clamped)
        }

        /** A fraction in 0..1 for a cell, as a pure function of it. */
        private fun jitter(first: Int, second: Int, seed: Long): Double {
            var mixed = seed xor (first.toLong() * 0x9E37_79B9_7F4A_7C15uL.toLong()) xor (second.toLong() * 0xC2B2_AE3D_27D4_EB4FuL.toLong())
            mixed = (mixed xor (mixed ushr 31)) * 0xBF58_476D_1CE4_E5B9uL.toLong()
            mixed = mixed xor (mixed ushr 29)
            return (mixed ushr 11).toDouble() / (1L shl 53).toDouble()
        }

        fun codec(self: Codec<TerrainField>): MapCodec<Gullied> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                self.fieldOf("base").forGetter(Gullied::base),
                Codec.LONG.fieldOf("seed").forGetter(Gullied::seed),
                Codec.DOUBLE.fieldOf("depth").forGetter(Gullied::depth),
                Codec.DOUBLE.fieldOf("cell_blocks").forGetter(Gullied::cellBlocks),
                Codec.INT.fieldOf("floor_y").forGetter(Gullied::floorY),
            ).apply(instance, ::Gullied)
        }
    }
}

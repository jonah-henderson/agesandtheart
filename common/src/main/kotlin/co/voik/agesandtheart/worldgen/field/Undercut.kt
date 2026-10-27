package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * [base] with its steep faces undercut: a notch worn into the bottom of a face, up to [depth] blocks in and
 * [height] blocks high, under a cap left standing — a sea notch where the face stands in water, an alcove where it
 * stands on dry ground.
 *
 * A column is behind a face where ground within [depth] blocks of it, along one of the four axes, stands at least
 * [SHORTEST_FACE] lower. The notch opens just over the foot of the face, or over [floorY] if the foot is under it,
 * and how far in it reaches varies along the face.
 */
data class Undercut(
    val base: TerrainField,
    val seed: Long,
    val depth: Double,
    val height: Double,
    val floorY: Int,
) : TerrainField {
    override val kind = FieldKind.UNDERCUT

    override val horizontalReach = base.horizontalReach

    override val samplesPerColumn = base.samplesPerColumn + 1

    private val columns = ColumnMemo(base::columnSpans)
    private val reachNoise = fieldNoise(seed, REACH_OCTAVE, REACH_AMPLITUDES)

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val standing = columns.at(worldX, worldZ)
        val top = standing.highestSolidY ?: return standing
        val reachHere = depth * (HALF + HALF * reachNoise.get(worldX / REACH_STRETCH, 0.0, worldZ / REACH_STRETCH).toDouble())
        val furthest = reachHere.roundToInt()
        if (furthest < 1) return standing
        val footY = footBelow(worldX, worldZ, top, furthest) ?: return standing
        val notchFloor = max(footY, floorY) + 1
        val notchRoof = notchFloor + height.roundToInt() - 1
        val leavesACap = top - notchRoof >= THINNEST_CAP
        if (!leavesACap) return standing
        return standing.subtract(Spans.of(notchFloor, notchRoof))
    }

    /** The highest foot of a face within [furthest] blocks along any axis, or null where this column fronts none. */
    private fun footBelow(worldX: Int, worldZ: Int, top: Int, furthest: Int): Int? {
        var footY: Int? = null
        for ((stepX, stepZ) in AXES) {
            for (distance in 1..furthest) {
                val there = columns.at(worldX + stepX * distance, worldZ + stepZ * distance).highestSolidY ?: continue
                if (top - there >= SHORTEST_FACE) {
                    footY = max(footY ?: there, there)
                    break
                }
            }
        }
        return footY
    }

    override fun resized(factor: Double, pivotY: Int) = copy(
        base = base.resized(factor, pivotY),
        floorY = scaledAbout(floorY, factor, pivotY),
    )

    companion object {
        /** How much lower ground has to stand, within the reach, for the rise to it to be a face. */
        private const val SHORTEST_FACE = 12

        /** The least rock left standing over a notch. */
        private const val THINNEST_CAP = 4

        private val AXES = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

        private const val REACH_OCTAVE = 0
        private val REACH_AMPLITUDES = listOf(1.0, 0.5)
        private const val REACH_STRETCH = 24.0
        private const val HALF = 0.5

        fun codec(self: Codec<TerrainField>): MapCodec<Undercut> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                self.fieldOf("base").forGetter(Undercut::base),
                Codec.LONG.fieldOf("seed").forGetter(Undercut::seed),
                Codec.DOUBLE.fieldOf("depth").forGetter(Undercut::depth),
                Codec.DOUBLE.fieldOf("height").forGetter(Undercut::height),
                Codec.INT.fieldOf("floor_y").forGetter(Undercut::floorY),
            ).apply(instance, ::Undercut)
        }
    }
}

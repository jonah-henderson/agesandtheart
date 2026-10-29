package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * [base] worn into terraces over [share] of the land, more where it is more worn: in patches, the top of each column
 * is drawn to flat treads [stepHeight] apart with a steep riser between, as strata worn back at different rates leave
 * it. The treads stand at the same heights wherever there are terraces, as strata do. Between a patch and the ground
 * around it the steps ease out over a band.
 *
 * Only the top span of a column moves, never by more than a step, and never on columns under [floorY].
 */
data class Terraced(
    val base: TerrainField,
    val seed: Long,
    val stepHeight: Double,
    /** What share of the land is terraced, from none to all of it. */
    val share: Double,
    val floorY: Int,
    /** How worn each place is; [share] is the ordinary amount's. */
    val wear: Wear = Wear.EVEN,
    /** What share of the land is terraced however worn it is, on top of [share]. */
    val unworn: Double = 0.0,
) : TerrainField {
    override val kind = FieldKind.TERRACED

    override val horizontalReach = base.horizontalReach

    override val samplesPerColumn = base.samplesPerColumn + 1

    private val patches = fieldNoise(seed, PATCH_OCTAVE, PATCH_AMPLITUDES)

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val standing = base.columnSpans(worldX, worldZ)
        val top = standing.ranges.lastOrNull() ?: return standing
        if (top.last < floorY || stepHeight <= 0.0) return standing
        val terracing = terracingAt(worldX, worldZ)
        if (terracing <= 0.0) return standing
        val height = (top.last - floorY).toDouble()
        val steps = height / stepHeight
        val step = floor(steps)
        val climbing = smoothstep((steps - step - (1.0 - RISER_SHARE)) / RISER_SHARE)
        val stepped = (step + climbing) * stepHeight
        val worn = (floorY + height + terracing * (stepped - height)).roundToInt().coerceAtLeast(top.first)
        if (worn == top.last) return standing
        return Spans.ofAscending(standing.ranges.dropLast(1) + listOf(top.first..worn))
    }

    /** One inside a patch, nothing outside, and between the two across the patch's edge. */
    private fun terracingAt(worldX: Int, worldZ: Int): Double {
        val shareHere = share * wear.at(worldX, worldZ) + unworn
        if (shareHere <= 0.0) return 0.0
        val threshold = 1.0 - 2.0 * shareHere.coerceAtMost(1.0)
        val reading = patches.get(worldX / PATCH_STRETCH, 0.0, worldZ / PATCH_STRETCH).toDouble()
        return smoothstep((reading - threshold) / PATCH_EDGE + HALF)
    }

    private fun smoothstep(share: Double): Double {
        val clamped = share.coerceIn(0.0, 1.0)
        return clamped * clamped * (3.0 - 2.0 * clamped)
    }

    override fun resized(factor: Double, pivotY: Int) = copy(
        base = base.resized(factor, pivotY),
        stepHeight = stepHeight * factor,
        floorY = scaledAbout(floorY, factor, pivotY),
        wear = wear.resized(factor),
    )

    companion object {
        /** The riser's share of a step; the rest is tread. */
        private const val RISER_SHARE = 0.3

        private const val PATCH_OCTAVE = 0
        private val PATCH_AMPLITUDES = listOf(1.0, 0.5)
        private const val PATCH_STRETCH = 900.0
        private const val PATCH_EDGE = 0.25
        private const val HALF = 0.5

        fun codec(self: Codec<TerrainField>): MapCodec<Terraced> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                self.fieldOf("base").forGetter(Terraced::base),
                Codec.LONG.fieldOf("seed").forGetter(Terraced::seed),
                Codec.DOUBLE.fieldOf("step_height").forGetter(Terraced::stepHeight),
                Codec.DOUBLE.fieldOf("share").forGetter(Terraced::share),
                Codec.INT.fieldOf("floor_y").forGetter(Terraced::floorY),
                Wear.FIELD.forGetter(Terraced::wear),
                Codec.DOUBLE.optionalFieldOf("unworn", 0.0).forGetter(Terraced::unworn),
            ).apply(instance, ::Terraced)
        }
    }
}

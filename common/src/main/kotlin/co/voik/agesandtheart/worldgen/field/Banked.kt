package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import kotlin.math.roundToInt

/**
 * [worn] held back from the water a landform carries: where [water] stands in a column, ground at or under its surface
 * stays as [unworn] had it, ground above is never worn down into it, and the wear eases in over [easing] blocks above
 * it, so a river's banks are not notched and no terrace beside it floods. Columns with no water are [worn]'s.
 */
data class Banked(val worn: TerrainField, val unworn: TerrainField, val water: TerrainField, val easing: Double) : TerrainField {
    override val kind = FieldKind.BANKED

    override val horizontalReach = worn.horizontalReach

    override val samplesPerColumn = worn.samplesPerColumn + water.samplesPerColumn

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val wornHere = worn.columnSpans(worldX, worldZ)
        val surface = water.columnSpans(worldX, worldZ).highestSolidY ?: return wornHere
        val standing = unworn.columnSpans(worldX, worldZ)
        val top = standing.ranges.lastOrNull() ?: return wornHere
        val aboveTheWater = top.last - surface
        if (aboveTheWater <= 0) return standing
        val wornTop = wornHere.highestSolidY ?: return standing
        val share = smoothstep(aboveTheWater / easing)
        if (share >= 1.0 && wornTop > surface) return wornHere
        val held = (top.last + (wornTop - top.last) * share).roundToInt().coerceAtLeast(maxOf(surface + 1, top.first))
        return Spans.ofAscending(standing.ranges.dropLast(1) + listOf(top.first..held))
    }

    override fun resized(factor: Double, pivotY: Int) = copy(
        worn = worn.resized(factor, pivotY),
        unworn = unworn.resized(factor, pivotY),
        water = water.resized(factor, pivotY),
        easing = easing * factor,
    )

    companion object {
        private fun smoothstep(share: Double): Double {
            val clamped = share.coerceIn(0.0, 1.0)
            return clamped * clamped * (3.0 - 2.0 * clamped)
        }

        fun codec(self: Codec<TerrainField>): MapCodec<Banked> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                self.fieldOf("worn").forGetter(Banked::worn),
                self.fieldOf("unworn").forGetter(Banked::unworn),
                self.fieldOf("water").forGetter(Banked::water),
                Codec.DOUBLE.fieldOf("easing").forGetter(Banked::easing),
            ).apply(instance, ::Banked)
        }
    }
}

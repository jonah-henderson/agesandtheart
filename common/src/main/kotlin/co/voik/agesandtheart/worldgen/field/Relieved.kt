package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import kotlin.math.max
import kotlin.math.min

/**
 * [base] with its height above [pivotY] stretched or squashed by [factor] — a landform's relief, as a word bends it
 * (`high landmass`, `subdued`). Everything at or below [pivotY] stays where it is, so a sea keeps its shore.
 *
 * A stretch never carries a column above [ceilingY]: one that would is stretched only as far as fits, as [Exaggerated]
 * does, so the tallest ground is squeezed under the top of the world rather than cut off by it.
 */
data class Relieved(val base: TerrainField, val pivotY: Int, val factor: Double, val ceilingY: Int) : TerrainField {
    override val kind = FieldKind.RELIEVED

    override val horizontalReach = base.horizontalReach

    override val samplesPerColumn = base.samplesPerColumn

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val standing = base.columnSpans(worldX, worldZ)
        val top = standing.highestSolidY ?: return standing
        val raises = factor > 1.0 && top > pivotY
        val fitting = if (raises) min(factor, max(1.0, (ceilingY - pivotY).toDouble() / (top - pivotY))) else factor
        return standing.stretchedAbove(pivotY, fitting)
    }

    override fun resized(factor: Double, pivotY: Int) = copy(base = base.resized(factor, pivotY))

    companion object {
        fun codec(self: Codec<TerrainField>): MapCodec<Relieved> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                self.fieldOf("base").forGetter(Relieved::base),
                Codec.INT.fieldOf("pivot_y").forGetter(Relieved::pivotY),
                Codec.DOUBLE.fieldOf("factor").forGetter(Relieved::factor),
                Codec.INT.fieldOf("ceiling_y").forGetter(Relieved::ceilingY),
            ).apply(instance, ::Relieved)
        }
    }
}

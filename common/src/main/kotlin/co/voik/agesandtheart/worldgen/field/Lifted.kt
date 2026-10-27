package co.voik.agesandtheart.worldgen.field

import co.voik.agesandtheart.worldgen.VerticalWindow
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import kotlin.math.roundToInt

/**
 * A landform moved so that its [datumY] stands at [toY]: every height shifted by the difference, and what stood above
 * the datum squeezed by [squeeze] about it, so a landform lifted with no room above it still fits under the top of the
 * world.
 */
data class Lift(val datumY: Int, val toY: Int, val squeeze: Double = 1.0) {
    val isNone: Boolean get() = datumY == toY && squeeze == 1.0

    /** Where a height of the landform's stands once lifted. */
    fun heightOf(y: Int): Int =
        if (y < datumY) y + (toY - datumY) else toY + ((y - datumY) * squeeze).roundToInt()

    /** [field] lifted, with rock lifted off its floor filled back down to it where [keepsFloor]. */
    fun over(field: TerrainField, keepsFloor: Boolean = true): TerrainField = Lifted(field, this, keepsFloor)

    companion object {
        /**
         * How far to squeeze what stands above [datumY] so that [topY], lifted to stand over [toY], still fits under the
         * top of the world: not at all where it fits already.
         */
        fun squeezeToFit(datumY: Int, toY: Int, topY: Int = VerticalWindow.HIGHEST_BLOCK_Y): Double {
            val above = (topY - datumY).toDouble()
            val room = (VerticalWindow.HIGHEST_BLOCK_Y - toY).toDouble()
            return if (above <= 0.0 || room >= above) 1.0 else (room / above).coerceAtLeast(0.0)
        }

        val CODEC: MapCodec<Lift> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.INT.fieldOf("datum_y").forGetter(Lift::datumY),
                Codec.INT.fieldOf("to_y").forGetter(Lift::toY),
                Codec.DOUBLE.optionalFieldOf("squeeze", 1.0).forGetter(Lift::squeeze),
            ).apply(instance, ::Lift)
        }
    }
}

/** [base] moved by [lift]. */
data class Lifted(val base: TerrainField, val lift: Lift, val keepsFloor: Boolean) : TerrainField {
    override val kind = FieldKind.LIFTED

    override val horizontalReach = base.horizontalReach

    override val samplesPerColumn = base.samplesPerColumn

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val standing = base.columnSpans(worldX, worldZ)
        if (standing.ranges.isEmpty()) return standing
        // Squeezed, two ranges may come to touch, which the union joins.
        val moved = Spans.EMPTY.union(
            Spans.ofAscending(standing.ranges.map { range -> lift.heightOf(range.first)..lift.heightOf(range.last) }),
        )
        val floor = standing.ranges.first().first
        val liftedOffItsFloor = keepsFloor && lift.heightOf(floor) > floor
        return if (liftedOffItsFloor) moved.union(Spans.of(floor, lift.heightOf(floor) - 1)) else moved
    }

    override fun resized(factor: Double, pivotY: Int) = copy(base = base.resized(factor, pivotY))

    companion object {
        fun codec(self: Codec<TerrainField>): MapCodec<Lifted> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                self.fieldOf("base").forGetter(Lifted::base),
                Lift.CODEC.forGetter(Lifted::lift),
                Codec.BOOL.fieldOf("keeps_floor").forGetter(Lifted::keepsFloor),
            ).apply(instance, ::Lifted)
        }
    }
}

package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * [base] with everything taken out of it but a crust [crustThickness] blocks deep and a floor up to
 * [floorY] — the rock of the world as a shell over one open void.
 *
 * **The crust is measured to the base's own open space in three dimensions, not down its column.** Keeping
 * the top of each column alone would leave a cliff face as a sheet one column thick: the low column keeps
 * its top, the high one keeps its top, and the face between them opens straight into the void. So a block is
 * kept wherever open space lies within [crustThickness] of it vertically in its own column or in any column
 * up to [crustThickness] away along X or Z. That seals cliffs and the undersides of overhangs the same way it
 * seals the ground.
 *
 * The base's columns are read many times over, so they are remembered like [Caved]'s.
 */
data class HollowedOut(
    val base: TerrainField,
    val crustThickness: Int,
    val floorY: Int,
) : TerrainField {
    override val kind = FieldKind.HOLLOWED_OUT
    override val horizontalReach = base.horizontalReach
    override val samplesPerColumn = base.samplesPerColumn

    private val floor = Spans.of(Spans.LOWEST_Y, floorY)
    private val baseColumns = ColumnMemo(base::columnSpans)
    private val memo = ColumnMemo(::crust)

    override fun columnSpans(worldX: Int, worldZ: Int): Spans = memo.spansAt(worldX, worldZ)

    private fun crust(worldX: Int, worldZ: Int): Spans {
        val rock = baseColumns.spansAt(worldX, worldZ)
        if (rock.ranges.isEmpty()) return rock
        val alongTheAxes = (1..crustThickness).flatMap { step ->
            listOf(worldX + step to worldZ, worldX - step to worldZ, worldX to worldZ + step, worldX to worldZ - step)
        }
        val nearOpenSpace = alongTheAxes.fold(openSpaceNear(worldX, worldZ)) { near, (x, z) ->
            near.union(openSpaceNear(x, z))
        }
        return rock.intersect(nearOpenSpace.union(floor))
    }

    /** The heights in this column within [crustThickness] of the base's open space. */
    private fun openSpaceNear(worldX: Int, worldZ: Int): Spans {
        val open = Spans.EVERYWHERE.subtract(baseColumns.spansAt(worldX, worldZ))
        return open.ranges.fold(Spans.EMPTY) { reach, run ->
            reach.union(Spans.of(run.first - crustThickness, run.last + crustThickness))
        }
    }

    override fun resized(factor: Double, pivotY: Int) = HollowedOut(
        base = base.resized(factor, pivotY),
        crustThickness = crustThickness,
        floorY = scaledAbout(floorY, factor, pivotY),
    )

    companion object {
        fun codec(self: Codec<TerrainField>): MapCodec<HollowedOut> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                self.fieldOf("base").forGetter(HollowedOut::base),
                Codec.INT.fieldOf("crust_thickness").forGetter(HollowedOut::crustThickness),
                Codec.INT.fieldOf("floor_y").forGetter(HollowedOut::floorY),
            ).apply(instance, ::HollowedOut)
        }
    }
}

package co.voik.agesandtheart.worldgen.field

import co.voik.agesandtheart.worldgen.Dislocation
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * [base], read in patches from somewhere else — see [Dislocation]. A column inside a patch answers with
 * the shape of a column far away, so the land breaks off at the patch's edge and carries on as another
 * country.
 *
 * Wraps the finished rock, for the reason [Fault] does: whatever the shape was weathered or carved by
 * moves with it. Not resizable by its own distances: they are in the world's blocks, not the template's.
 */
data class Dislocated(val base: TerrainField, val dislocation: Dislocation) : TerrainField {
    override val kind = FieldKind.DISLOCATED

    override val horizontalReach = Double.POSITIVE_INFINITY

    override val samplesPerColumn = base.samplesPerColumn + 2

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val shift = dislocation.shiftAt(worldX, worldZ)
        return if (shift.isNone) base.columnSpans(worldX, worldZ) else base.columnSpans(worldX + shift.x, worldZ + shift.z)
    }

    override fun resized(factor: Double, pivotY: Int) = copy(base = base.resized(factor, pivotY))

    companion object {
        /** [base] itself where nothing is dislocated. */
        fun over(base: TerrainField, dislocation: Dislocation): TerrainField =
            if (dislocation.isNone) base else Dislocated(base, dislocation)

        fun codec(self: Codec<TerrainField>): MapCodec<Dislocated> =
            RecordCodecBuilder.mapCodec { instance ->
                instance.group(
                    self.fieldOf("base").forGetter(Dislocated::base),
                    Dislocation.CODEC.fieldOf("dislocation").forGetter(Dislocated::dislocation),
                ).apply(instance, ::Dislocated)
            }
    }
}

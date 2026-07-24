package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder

/** Solid where *any* child is solid — the CSG union. The workhorse: an island is a body ∪ its spires. */
data class Union(val fields: List<TerrainField>) : TerrainField {
    override val kind = FieldKind.UNION
    override val horizontalReach = fields.maxOfOrNull { it.horizontalReach } ?: 0.0

    override fun columnSpans(worldX: Int, worldZ: Int): Spans =
        fields.fold(Spans.EMPTY) { accumulated, field -> accumulated.union(field.columnSpans(worldX, worldZ)) }

    companion object {
        fun codec(self: Codec<TerrainField>): MapCodec<Union> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                self.listOf().fieldOf("fields").forGetter(Union::fields),
            ).apply(instance, ::Union)
        }
    }
}

/** Solid where [base] is solid but [cut] is not — the CSG difference (caves, canyons, cliff edges). */
data class Subtract(val base: TerrainField, val cut: TerrainField) : TerrainField {
    override val kind = FieldKind.SUBTRACT
    override val horizontalReach = base.horizontalReach // subtracting can only remove solidity

    override fun columnSpans(worldX: Int, worldZ: Int): Spans =
        base.columnSpans(worldX, worldZ).subtract(cut.columnSpans(worldX, worldZ))

    companion object {
        fun codec(self: Codec<TerrainField>): MapCodec<Subtract> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                self.fieldOf("base").forGetter(Subtract::base),
                self.fieldOf("cut").forGetter(Subtract::cut),
            ).apply(instance, ::Subtract)
        }
    }
}

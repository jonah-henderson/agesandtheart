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

    override fun resized(factor: Double, pivotY: Int) = Union(fields.map { it.resized(factor, pivotY) })

    companion object {
        fun codec(self: Codec<TerrainField>): MapCodec<Union> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                self.listOf().fieldOf("fields").forGetter(Union::fields),
            ).apply(instance, ::Union)
        }
    }
}

/**
 * Solid only where *every* child is solid — the CSG intersection. The shaping workhorse: it clips one
 * shape to another (a hillside cut to a circle becomes a mesa) and, given [HalfSpace] children, *is*
 * the toolkit's convex polyhedron.
 */
data class Intersect(val fields: List<TerrainField>) : TerrainField {
    override val kind = FieldKind.INTERSECT

    // Intersecting can only remove solidity, so the tightest child bounds the result.
    override val horizontalReach = fields.minOfOrNull { it.horizontalReach } ?: 0.0

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        if (fields.isEmpty()) return Spans.EMPTY
        return fields.fold(Spans.EVERYWHERE) { accumulated, field ->
            if (accumulated.ranges.isEmpty()) accumulated else accumulated.intersect(field.columnSpans(worldX, worldZ))
        }
    }

    override fun resized(factor: Double, pivotY: Int) = Intersect(fields.map { it.resized(factor, pivotY) })

    companion object {
        fun codec(self: Codec<TerrainField>): MapCodec<Intersect> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                self.listOf().fieldOf("fields").forGetter(Intersect::fields),
            ).apply(instance, ::Intersect)
        }
    }
}

/** Solid where [base] is solid but [cut] is not — the CSG difference (caves, canyons, cliff edges). */
data class Subtract(val base: TerrainField, val cut: TerrainField) : TerrainField {
    override val kind = FieldKind.SUBTRACT
    override val horizontalReach = base.horizontalReach // subtracting can only remove solidity

    override fun columnSpans(worldX: Int, worldZ: Int): Spans =
        base.columnSpans(worldX, worldZ).subtract(cut.columnSpans(worldX, worldZ))

    override fun resized(factor: Double, pivotY: Int) =
        Subtract(base.resized(factor, pivotY), cut.resized(factor, pivotY))

    companion object {
        fun codec(self: Codec<TerrainField>): MapCodec<Subtract> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                self.fieldOf("base").forGetter(Subtract::base),
                self.fieldOf("cut").forGetter(Subtract::cut),
            ).apply(instance, ::Subtract)
        }
    }
}

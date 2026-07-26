package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder

/** Solid where *any* child is solid — the CSG union. The workhorse: an island is a body ∪ its spires. */
data class Union(val fields: List<TerrainField>) : TerrainField {
    override val kind = FieldKind.UNION
    override val horizontalReach = fields.maxOfOrNull { it.horizontalReach } ?: 0.0

    // Every child is asked, always: a union cannot know a later one adds nothing. So ordering these
    // would buy nothing, and unlike Intersect this genuinely pays for all of them.
    override val samplesPerColumn = fields.sumOf { it.samplesPerColumn }

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

    override val samplesPerColumn = fields.sumOf { it.samplesPerColumn }

    /**
     * The children in the order it is cheapest to ask them, settled once here rather than per column.
     *
     * Intersection is order-independent as a *result*, but emphatically not as a *cost*: the fold below
     * stops the moment nothing is left solid, so an analytic bound asked first skips a sampled child
     * entirely on every column it misses — which, for scattered shapes, is nearly all of them. Sorting
     * here rather than trusting the author to write them in a good order means the tree cannot be
     * built wrong, only slowly. [fields] keeps its written order, so what serialises is unchanged.
     */
    private val cheapestFirst = fields.sortedBy { it.samplesPerColumn }

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        if (fields.isEmpty()) return Spans.EMPTY
        return cheapestFirst.fold(Spans.EVERYWHERE) { accumulated, field ->
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

    override val samplesPerColumn = base.samplesPerColumn + cut.samplesPerColumn

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val solid = base.columnSpans(worldX, worldZ)
        // Nothing here to cut, so do not pay to find out what would have been removed — which is the
        // whole cost when the cut is a sampled field and the base is scattered.
        if (solid.ranges.isEmpty()) return solid
        return solid.subtract(cut.columnSpans(worldX, worldZ))
    }

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

package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.util.RandomSource
import net.minecraft.world.level.levelgen.XoroshiroRandomSource

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
     * The children in the order it is cheapest to ask them, settled once rather than per column.
     *
     * Intersection is order-independent as a *result* and not as a *cost*: the fold stops the moment
     * nothing is solid, so an analytic bound asked first skips a sampled child on every column it misses.
     * Sorted here rather than trusted to the author, so the tree cannot be built wrong, only slowly.
     * [fields] keeps its written order, so what serialises is unchanged.
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

/**
 * [child], present or absent **for the whole Age** with probability [probability]. The draw is resolved
 * once from the seed at construction, since `columnSpans` is pure and an Age must rebuild identically:
 * per column would riddle a shape with holes, and per instance already exists as [Density.keepProbability].
 *
 * Two traps:
 *
 * - **Never use this as an [Instanced] template.** Templates are queried in local coordinates and this
 *   resolves once, so every copy gets the *same* answer — all present or all absent.
 * - **Salt the seed per node.** Two nodes handed one seed draw the same number and agree every time,
 *   which reads as coincidence rather than the bug it is.
 *
 * A single-child [Choose] cannot replace this: its count is drawn uniformly, so `0..1` is a coin flip
 * with nowhere to put a probability of 0.3.
 */
data class Chance(val child: TerrainField, val probability: Double, val seed: Long) : TerrainField {
    override val kind = FieldKind.CHANCE

    // Derived from constructor arguments only, so `equals`, `hashCode` and `copy` all stay honest: two Chance
    // nodes agreeing on child, probability and seed are the same node, and re-deriving gives the same verdict.
    private val present: Boolean = XoroshiroRandomSource(seed).nextDouble() < probability

    override val horizontalReach = if (present) child.horizontalReach else 0.0

    override val samplesPerColumn = if (present) child.samplesPerColumn else 0

    override fun columnSpans(worldX: Int, worldZ: Int): Spans =
        if (present) child.columnSpans(worldX, worldZ) else Spans.EMPTY

    /** Carries the child. The verdict re-derives identically, since nothing the draw reads has changed. */
    override fun resized(factor: Double, pivotY: Int) = copy(child = child.resized(factor, pivotY))

    companion object {
        fun codec(self: Codec<TerrainField>): MapCodec<Chance> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                self.fieldOf("child").forGetter(Chance::child),
                Codec.DOUBLE.fieldOf("probability").forGetter(Chance::probability),
                Codec.LONG.fieldOf("seed").forGetter(Chance::seed),
            ).apply(instance, ::Chance)
        }
    }
}

/**
 * Places between [leastPlaced] and [mostPlaced] of its [alternatives] and skips the rest — *"selects some subset
 * of its children to place and others to skip"*.
 *
 * Drawn once from [seed] at construction, for the reasons set out on [Chance]; both of its traps apply
 * here unchanged.
 *
 * **Not `Union` of [Chance]**, because independent draws cannot express a *constraint across the
 * children* — `leastPlaced = mostPlaced = 2` places exactly two of five, which no per-child probability
 * can promise.
 *
 * One node covers all three spellings:
 *
 * | want | spelling |
 * |---|---|
 * | exactly *k* of *n* | `leastPlaced = mostPlaced = k` |
 * | one of *n*, weighted | `leastPlaced = mostPlaced = 1`, with [Alternative.weight]s |
 * | somewhere between | `leastPlaced = a`, `mostPlaced = b` |
 *
 * Weights are per alternative rather than a parallel list, so a length mismatch is not expressible. A
 * weight of zero means *never*, and where that leaves fewer eligible alternatives than the count asks
 * for, every eligible one is placed and no more — a count is a request.
 */
data class Choose(
    val alternatives: List<Alternative>,
    val leastPlaced: Int,
    val mostPlaced: Int,
    val seed: Long,
) : TerrainField {
    /** One candidate and how much of the draw it claims. Naming `Scatter`'s convention for count bounds. */
    data class Alternative(val field: TerrainField, val weight: Double = 1.0)

    override val kind = FieldKind.CHOOSE

    // Derived from constructor arguments only — see the note on `Chance.present`.
    private val chosen: List<TerrainField> = draw()

    override val horizontalReach = chosen.maxOfOrNull { it.horizontalReach } ?: 0.0

    // The kept children's, summed: like a Union, every one of them is asked on every column.
    override val samplesPerColumn = chosen.sumOf { it.samplesPerColumn }

    override fun columnSpans(worldX: Int, worldZ: Int): Spans =
        chosen.fold(Spans.EMPTY) { accumulated, field -> accumulated.union(field.columnSpans(worldX, worldZ)) }

    /**
     * Carries the alternatives. **The same subset comes back**, since the draw reads only the seed, the
     * bounds and the weights — so a resized `Choose` is one choice at a different size, not a fresh roll.
     */
    override fun resized(factor: Double, pivotY: Int) = copy(
        alternatives = alternatives.map { it.copy(field = it.field.resized(factor, pivotY)) },
    )

    /** Weighted selection without replacement, of a count drawn uniformly between the two bounds. */
    private fun draw(): List<TerrainField> {
        val eligible = alternatives.filter { it.weight > 0.0 }.toMutableList()
        if (eligible.isEmpty()) return emptyList()

        val random = XoroshiroRandomSource(seed)
        val span = (mostPlaced - leastPlaced).coerceAtLeast(0)
        val wanted = (leastPlaced + random.nextInt(span + 1)).coerceIn(0, eligible.size)

        val taken = ArrayList<TerrainField>(wanted)
        repeat(wanted) {
            taken += eligible.removeAt(pickWeighted(eligible, random)).field
        }
        return taken
    }

    private fun pickWeighted(eligible: List<Alternative>, random: RandomSource): Int {
        var remaining = random.nextDouble() * eligible.sumOf { it.weight }
        // Stops one short of the end so floating-point drift lands on the last alternative rather than past it.
        for (index in 0..<eligible.size - 1) {
            remaining -= eligible[index].weight
            if (remaining < 0.0) return index
        }
        return eligible.size - 1
    }

    companion object {
        fun codec(self: Codec<TerrainField>): MapCodec<Choose> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                alternativeCodec(self).listOf().fieldOf("alternatives").forGetter(Choose::alternatives),
                Codec.INT.fieldOf("least_placed").forGetter(Choose::leastPlaced),
                Codec.INT.fieldOf("most_placed").forGetter(Choose::mostPlaced),
                Codec.LONG.fieldOf("seed").forGetter(Choose::seed),
            ).apply(instance, ::Choose)
        }

        private fun alternativeCodec(self: Codec<TerrainField>): Codec<Alternative> =
            RecordCodecBuilder.create { instance ->
                instance.group(
                    self.fieldOf("field").forGetter(Alternative::field),
                    // Optional, so the common "any of these, equally likely" needs no weights written at all.
                    Codec.DOUBLE.optionalFieldOf("weight", 1.0).forGetter(Alternative::weight),
                ).apply(instance, ::Alternative)
            }
    }
}

/**
 * [base], moved [lift] blocks up the world and otherwise untouched.
 *
 * Wrapping rather than editing a preset's own height constants, which `Weathering.SPIRE` derives its band
 * from: changing them in place would move the shape for **every** Age using that terrain, including ones
 * whose dimension type has no headroom (see [co.voik.agesandtheart.worldgen.VerticalWindow]).
 *
 * **A raised shape must be weathered by an equally raised profile**, erosion's keel and band being
 * absolute heights — which is what `Weathered.spire(base, lift)` keeps in step.
 */
data class Raised(val base: TerrainField, val lift: Int) : TerrainField {
    override val kind = FieldKind.RAISED
    override val horizontalReach = base.horizontalReach // moving vertically changes nothing horizontally

    override val samplesPerColumn = base.samplesPerColumn

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val standing = base.columnSpans(worldX, worldZ)
        if (lift == 0 || standing.ranges.isEmpty()) return standing
        // Order is preserved by a uniform shift, so no normalising pass is needed.
        return Spans.ofAscending(standing.ranges.map { it.first + lift..it.last + lift })
    }

    /**
     * Scaling reaches the child, and the lift scales with it: a lift is a distance in the space the
     * child's own heights live in, so leaving it alone would move the shape relative to itself.
     */
    override fun resized(factor: Double, pivotY: Int) =
        Raised(base.resized(factor, pivotY), (lift * factor).toInt())

    companion object {
        fun codec(self: Codec<TerrainField>): MapCodec<Raised> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                self.fieldOf("base").forGetter(Raised::base),
                Codec.INT.fieldOf("lift").forGetter(Raised::lift),
            ).apply(instance, ::Raised)
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

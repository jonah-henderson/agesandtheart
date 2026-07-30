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

/**
 * [child], present or absent for the whole Age with probability [probability] — *"maybe place or maybe omit"*.
 *
 * ## The draw is resolved once, from the seed, and that is the whole design
 *
 * `columnSpans` is a pure function of position and Ages are required to rebuild identically on every open, so
 * "randomly" cannot mean a draw at query time. It means **a deterministic function of a seed**, and the unit it
 * is keyed on is the choice that gives this node its character. Per column would riddle a shape with
 * salt-and-pepper holes; per instance already exists (see the trap below). Per **Age** is what Jonah asked for:
 * `Chance(cone, 0.3)` means three Ages in ten have a central cone, and within any one Age it is uniform.
 *
 * So the draw happens at construction. Everything downstream — [horizontalReach], [samplesPerColumn],
 * [columnSpans] — then reports on what was actually kept, exactly rather than defensively, because the object is
 * immutable and the answer can never change under it.
 *
 * ## Two traps worth naming
 *
 * **Do not use this as an [Instanced] template.** Templates are queried in local coordinates and this resolves
 * once, so every copy would get the *same* answer — all present or all absent, which is the identical-clones
 * failure `Noise3D`'s documentation describes from the other direction. Per-instance omission is
 * [Density.keepProbability]'s job and has been since instancing landed; per-instance *choice between* shapes is
 * `Instanced`'s own template pick.
 *
 * **Salt the seed per node.** Two nodes handed the same seed draw the same number and so agree every time, which
 * reads as a coincidence rather than the bug it is. Derive them apart the way `Weathering` does for its second
 * noise — `seed * 31 + 17` — or from separate named constants.
 *
 * A single-child [Choose] cannot replace this: its count is drawn *uniformly* over a range, so `0..1` is a coin
 * flip and there is nowhere to put a probability of 0.3.
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
 * Drawn once from [seed] at construction, for the reasons set out on [Chance]; both of that class's traps apply
 * here unchanged.
 *
 * ## Why this is a node and not `Union` of [Chance]
 *
 * If every child decided independently, `Choose` would be exactly `Union(Chance(a, p), Chance(b, q), …)` and
 * would deserve the fate of the `Invert` idea in `notes/terrain-architecture.md` — proposed, then found to
 * collapse into what already existed. What independent draws cannot express is a **constraint across the
 * children**, and that is what this carries: a count. `leastPlaced = mostPlaced = 2` places exactly two of five,
 * which no per-child probability can promise.
 *
 * One node covers all three spellings Jonah wanted:
 *
 * | want | spelling |
 * |---|---|
 * | exactly *k* of *n* | `leastPlaced = mostPlaced = k` |
 * | one of *n*, weighted | `leastPlaced = mostPlaced = 1`, with [Alternative.weight]s |
 * | somewhere between | `leastPlaced = a`, `mostPlaced = b` |
 *
 * Weights are per alternative rather than a parallel list, so a length mismatch is not expressible; they default
 * to 1.0, which is the plain "any of these, equally likely". A weight of zero means *never*, and if that leaves
 * fewer eligible alternatives than the count asks for, every eligible one is placed and no more — a count is a
 * request, and the alternatives are what there is to satisfy it with.
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
     * Carries the alternatives. **The same subset comes back**, because the draw reads only the seed, the count
     * bounds and the weights — none of which resizing touches. A resized `Choose` is therefore the same choice
     * at a different size, rather than a fresh roll of the dice at every instanced size.
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
 * [base], moved [lift] blocks up the world and otherwise untouched — the one transform the toolkit was missing.
 *
 * `resized` could already make a shape bigger about a pivot, but nothing could simply *move* one, so a preset's
 * altitude was wherever its constants happened to put it. That became a problem when the Spire archipelago
 * needed to sit higher: its heights are `const val`s that `Weathering.SPIRE` derives its own band from, so
 * changing them in place would have moved the shape for **every** Age using `spire_islands` — including ones
 * whose dimension type has no headroom for it (see [co.voik.agesandtheart.worldgen.VerticalWindow]).
 *
 * Wrapping instead keeps the preset's tuning exactly where it is and makes the altitude a decision of whoever
 * assembles the world. Note that a wrapped shape must be weathered by an equally raised profile — erosion's
 * keel and band are absolute heights — which is what `Weathered.spire(base, lift)` exists to keep in step.
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
     * Scaling reaches the child, and the lift scales with it.
     *
     * A lift is a distance in the same space the child's own heights live in, so leaving it alone would move
     * the shape relative to itself — exactly the drift this class exists to avoid.
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

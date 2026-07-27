package co.voik.agesandtheart.worldgen.field

/**
 * The solid vertical intervals of a single world column, kept normalised: non-overlapping,
 * non-touching, ascending. This is the tier-1 "analytic span" currency the field toolkit fills
 * chunks from and answers surface-height queries with — see `notes/terrain-architecture.md`.
 *
 * Pure and loader-agnostic (no Minecraft types), so it's unit-testable without a running server.
 */
class Spans private constructor(val ranges: List<IntRange>) {

    /** The top of the highest solid interval, or `null` when this column is empty. */
    val highestSolidY: Int? = ranges.lastOrNull()?.last

    fun contains(y: Int): Boolean = ranges.any { y in it }

    /**
     * The top of the rock standing over [y] — the height of the lowest solid interval that reaches [y]
     * or above. `null` when nothing is solid up there, i.e. [y] is under open sky.
     *
     * This is *how buried* a point is, and it is deliberately not [highestSolidY]: for a point in a
     * cave it answers the roof of the rock above rather than the cave floor, and for a point beside a
     * spire it answers that column's own low ceiling rather than the distant summit. Ranges are
     * normalised ascending, so the first one that reaches [y] is the nearest.
     */
    fun roofOver(y: Int): Int? = ranges.firstOrNull { it.last >= y }?.last

    /**
     * This column moved [blocks] up, or down when negative.
     *
     * Exact and cheap: translating an interval is adding to both its ends, so nothing is resampled and
     * the normalised order and gaps survive untouched. That is precisely why an instance's *lift* can
     * be drawn per copy where its *size* cannot — resizing a built shape at a fractional rate stretches
     * its one-block staircase into uneven steps, which is why [TerrainField.resized] pre-builds sizes
     * instead. A lift has no such problem and needs no pre-building.
     */
    fun shifted(blocks: Int): Spans =
        if (blocks == 0 || ranges.isEmpty()) this
        else ofAscending(ranges.map { (it.first + blocks)..(it.last + blocks) })

    /** Solid where either column is solid. */
    fun union(other: Spans): Spans = normalise(ranges + other.ranges)

    /**
     * Solid only where *both* columns are solid — the CSG intersection. Both sides are normalised and
     * walked in order, so the overlaps come out normalised too.
     */
    fun intersect(other: Spans): Spans {
        if (ranges.isEmpty() || other.ranges.isEmpty()) return EMPTY
        val overlaps = ArrayList<IntRange>(minOf(ranges.size, other.ranges.size))
        var mine = 0
        var theirs = 0
        while (mine < ranges.size && theirs < other.ranges.size) {
            val ours = ranges[mine]
            val yours = other.ranges[theirs]
            val low = maxOf(ours.first, yours.first)
            val high = minOf(ours.last, yours.last)
            if (low <= high) overlaps += low..high
            // Retire whichever ends first; the other may still overlap what comes next.
            if (ours.last < yours.last) mine++ else theirs++
        }
        return if (overlaps.isEmpty()) EMPTY else Spans(overlaps)
    }

    /**
     * Solid where this column is solid but the cuts are not.
     *
     * Both sides are normalised ascending, so this is one ordered walk like [intersect] — each side
     * read once, one list built. It used to re-derive the whole column *per cut range*, allocating a
     * fresh list each pass and another for every range that survived it, which is invisible while a
     * cut is one interval and quadratic-ish once it is a cave system with a dozen. That is the shape
     * a [co.voik.agesandtheart.worldgen.field.Noise3D] cut has, and it made this a hot spot.
     */
    fun subtract(cuts: Spans): Spans {
        if (ranges.isEmpty() || cuts.ranges.isEmpty()) return this
        val kept = ArrayList<IntRange>(ranges.size + cuts.ranges.size)
        var firstLiveCut = 0
        for (range in ranges) {
            // Cuts ending below this range can never meet it or any later one, since both sides ascend.
            while (firstLiveCut < cuts.ranges.size && cuts.ranges[firstLiveCut].last < range.first) firstLiveCut++
            var low = range.first
            var cutIndex = firstLiveCut
            while (cutIndex < cuts.ranges.size && low <= range.last) {
                val cut = cuts.ranges[cutIndex]
                if (cut.first > range.last) break
                if (cut.first > low) kept += low..minOf(cut.first - 1, range.last)
                low = maxOf(low, cut.last + 1)
                cutIndex++
            }
            if (low <= range.last) kept += low..range.last
        }
        // Pieces come out ascending, and are separated either by the cut between them or by the gap
        // that already separated their parent ranges — the invariant, without a normalising pass.
        return ofAscending(kept)
    }

    companion object {
        val EMPTY = Spans(emptyList())

        /**
         * The toolkit's "all the way down / all the way up". Far outside any Minecraft world height —
         * the generator clips to the real one — but nowhere near `Int` overflow, so span arithmetic on
         * an unbounded shape like [HalfSpace] stays safe.
         */
        const val LOWEST_Y = -4096
        const val HIGHEST_Y = 4096

        /** Solid everywhere in the column. */
        val EVERYWHERE = Spans(listOf(LOWEST_Y..HIGHEST_Y))

        /** A single interval, inclusive; empty when [high] < [low]. */
        fun of(low: Int, high: Int): Spans = if (high < low) EMPTY else Spans(listOf(low..high))

        /**
         * Intervals the caller already knows satisfy the invariant — ascending, disjoint, and separated
         * by at least one empty level. Skips the sort-and-merge of [normalise], which matters for a field
         * that walks a column and emits runs as it goes, since such a walk cannot produce anything else.
         *
         * **The caller owns the invariant.** Handing this unsorted or touching ranges corrupts every
         * later union, intersect and subtract, quietly. Use [normalise] via [of]/[union] when unsure.
         */
        fun ofAscending(ranges: List<IntRange>): Spans = if (ranges.isEmpty()) EMPTY else Spans(ranges)

        /** Merge arbitrary integer ranges into the normalised invariant (sorted, merged, disjoint). */
        private fun normalise(input: List<IntRange>): Spans {
            val sorted = input.filter { !it.isEmpty() }.sortedBy { it.first }
            if (sorted.isEmpty()) return EMPTY
            val merged = ArrayList<IntRange>()
            var current = sorted.first()
            for (range in sorted.drop(1)) {
                current = if (range.first <= current.last + 1) {
                    current.first..maxOf(current.last, range.last)
                } else {
                    merged += current
                    range
                }
            }
            merged += current
            return Spans(merged)
        }
    }
}

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

    /** Solid where either column is solid. */
    fun union(other: Spans): Spans = normalise(ranges + other.ranges)

    /** Solid where this column is solid but the cuts are not. */
    fun subtract(cuts: Spans): Spans {
        var remaining = ranges
        for (cut in cuts.ranges) {
            remaining = remaining.flatMap { it.without(cut) }
        }
        // Cutting only splits/shrinks existing ranges in place, so order and disjointness survive.
        return Spans(remaining)
    }

    private fun IntRange.without(cut: IntRange): List<IntRange> {
        if (cut.last < first || cut.first > last) return listOf(this)
        val pieces = ArrayList<IntRange>(2)
        if (first < cut.first) pieces += first..minOf(cut.first - 1, last)
        if (last > cut.last) pieces += maxOf(cut.last + 1, first)..last
        return pieces
    }

    companion object {
        val EMPTY = Spans(emptyList())

        /** A single interval, inclusive; empty when [high] < [low]. */
        fun of(low: Int, high: Int): Spans = if (high < low) EMPTY else Spans(listOf(low..high))

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

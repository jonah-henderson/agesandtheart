package co.voik.agesandtheart.preview

import co.voik.agesandtheart.worldgen.field.Spans
import kotlin.random.Random

/**
 * Differential check of [Spans] interval algebra against a brute-force per-block reference.
 *
 * The reference is deliberately not the previous implementation: it asks, block by block, the question
 * the operation is *defined* by, so it cannot inherit a bug from what it is checking.
 */
fun main() {
    val random = Random(CHECK_SEED)
    var checked = 0

    repeat(CASES) {
        val base = randomSpans(random)
        val cut = randomSpans(random)

        verify("subtract", base, cut) { inBase, inCut -> inBase && !inCut }
        verify("intersect", base, cut) { inBase, inCut -> inBase && inCut }
        verify("union", base, cut) { inBase, inCut -> inBase || inCut }
        checked += 3
    }

    println("$checked operations agreed with the per-block reference over $CASES random span pairs.")
}

private fun verify(name: String, base: Spans, cut: Spans, expected: (Boolean, Boolean) -> Boolean) {
    val actual = when (name) {
        "subtract" -> base.subtract(cut)
        "intersect" -> base.intersect(cut)
        else -> base.union(cut)
    }
    for (y in LOW - 2..HIGH + 2) {
        val want = expected(base.contains(y), cut.contains(y))
        check(actual.contains(y) == want) { "$name disagreed at y=$y\n  base ${base.ranges}\n  cut  ${cut.ranges}\n  got  ${actual.ranges}" }
    }
    // The normalised invariant every later operation relies on: ascending, disjoint, and not touching.
    actual.ranges.zipWithNext { earlier, later ->
        check(earlier.last + 1 < later.first) { "$name left touching or unordered ranges: ${actual.ranges}" }
    }
    actual.ranges.forEach { check(!it.isEmpty()) { "$name left an empty range: ${actual.ranges}" } }
}

/** Built by union so it is normalised however the pieces overlap — including into nothing at all. */
private fun randomSpans(random: Random): Spans {
    var spans = Spans.EMPTY
    repeat(random.nextInt(0, MAX_PIECES)) {
        val start = random.nextInt(LOW, HIGH)
        spans = spans.union(Spans.of(start, start + random.nextInt(0, MAX_PIECE_LENGTH)))
    }
    return spans
}

private const val CHECK_SEED = 20260726L
private const val CASES = 20_000
private const val LOW = -20
private const val HIGH = 20
private const val MAX_PIECES = 6
private const val MAX_PIECE_LENGTH = 7

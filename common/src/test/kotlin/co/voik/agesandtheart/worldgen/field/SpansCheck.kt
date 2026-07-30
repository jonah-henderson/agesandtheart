package co.voik.agesandtheart.worldgen.field

import io.kotest.core.spec.style.FunSpec
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.pair
import io.kotest.property.checkAll

/**
 * Differential check of [Spans] interval algebra against a brute-force per-block reference. The reference
 * asks, block by block, the question each operation is *defined* by, so it cannot inherit a bug from what
 * it is checking.
 *
 * **The generated value is the recipe — a list of `(start, length)` pairs — not the [Spans].** `Spans` has
 * no `toString`, so a failure reported `Spans@724c5cbe` and told you nothing; and a value built inside an
 * `arbitrary { }` block carries no shrinker, so nothing shrank. Pairs print legibly and are a shape Kotest
 * knows how to shrink.
 */
class SpansCheck : FunSpec({

    val operations = listOf<Triple<String, (Spans, Spans) -> Spans, (Boolean, Boolean) -> Boolean>>(
        Triple("subtract", Spans::subtract) { inBase, inCut -> inBase && !inCut },
        Triple("intersect", Spans::intersect) { inBase, inCut -> inBase && inCut },
        Triple("union", Spans::union) { inBase, inCut -> inBase || inCut },
    )

    for ((name, operation, expected) in operations) {
        test("$name agrees with a per-block reference") {
            checkAll(PropTestConfig(seed = CHECK_SEED), arbitraryPieces, arbitraryPieces) { basePieces, cutPieces ->
                val base = spansOf(basePieces)
                val cut = spansOf(cutPieces)
                val actual = operation(base, cut)

                for (y in LOW - 2..HIGH + 2) {
                    val want = expected(base.contains(y), cut.contains(y))
                    check(actual.contains(y) == want) {
                        "$name disagreed at y=$y\n  base ${base.ranges}\n  cut  ${cut.ranges}\n  got  ${actual.ranges}"
                    }
                }

                // The normalised invariant every later operation relies on: ascending, disjoint, not touching.
                actual.ranges.zipWithNext { earlier, later ->
                    check(earlier.last + 1 < later.first) {
                        "$name left touching or unordered ranges: ${actual.ranges}"
                    }
                }
                actual.ranges.forEach {
                    check(!it.isEmpty()) { "$name left an empty range: ${actual.ranges}" }
                }
            }
        }
    }
})

/** A recipe for a [Spans]: each pair is one piece, as a start and a length. */
private val arbitraryPieces = Arb.list(
    Arb.pair(Arb.int(LOW, HIGH), Arb.int(0, MAX_PIECE_LENGTH)),
    0..MAX_PIECES,
)

/** Built by union so it is normalised however the pieces overlap — including into nothing at all. */
private fun spansOf(pieces: List<Pair<Int, Int>>): Spans =
    pieces.fold(Spans.EMPTY) { spans, (start, length) -> spans.union(Spans.of(start, start + length)) }

private const val CHECK_SEED = 20260726L
private const val LOW = -20
private const val HIGH = 20
private const val MAX_PIECES = 6
private const val MAX_PIECE_LENGTH = 7

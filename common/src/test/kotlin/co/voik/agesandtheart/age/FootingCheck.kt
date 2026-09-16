package co.voik.agesandtheart.age

import co.voik.agesandtheart.generation.Ages
import io.kotest.core.spec.style.StringSpec

/**
 * Which columns the arrival search tries, and in what order — the whole of what it costs.
 *
 * Every candidate is a full run of the generator's density functions, measured at about two milliseconds,
 * so the count is a time budget wearing a different unit. An Age with no land above the waterline walks all
 * of them to say so, which is the case these bound.
 */
class FootingCheck : StringSpec({

    val columns = Ages.candidateColumns().toList()
    fun distanceOf(column: Pair<Int, Int>) = maxOf(kotlin.math.abs(column.first), kotlin.math.abs(column.second))

    "no column is tried twice" {
        check(columns.size == columns.toSet().size) {
            "the search tries ${columns.size - columns.toSet().size} columns twice"
        }
    }

    "the whole search stays inside its budget" {
        // The worst case is an Age with no land anywhere, which samples every one of these.
        check(columns.size <= MOST_COLUMNS) {
            "the search may sample ${columns.size} columns, about ${columns.size * 2}ms of density functions"
        }
    }

    "the origin is tried first, so an Age with land underfoot costs one column" {
        check(columns.first() == 0 to 0) { "the search begins at ${columns.first()} rather than the origin" }
    }

    "somewhere far out is still reachable, or a distant landmass cannot be found at all" {
        check(columns.any { distanceOf(it) >= FAR }) {
            "nothing is tried further than ${columns.maxOf(::distanceOf)} blocks, so an Age's only land may be missed"
        }
    }

    "distant land is found early, not after most of the lattice" {
        val far = columns.indexOfFirst { distanceOf(it) >= FAR } + 1
        check(far in 1..EARLY) {
            "land $FAR blocks out is found after $far columns, which is not the wide lattice being tried first"
        }
    }
}) {
    private companion object {
        /** How far out the search reaches. */
        const val FAR = 288

        /** About a second of density functions, which is as long as opening a book may spend looking. */
        const val MOST_COLUMNS = 400

        /** Land at the far edge should cost a small fraction of the budget, not most of it. */
        const val EARLY = 160
    }
}

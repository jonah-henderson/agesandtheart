package co.voik.agesandtheart.age

import io.kotest.core.spec.style.StringSpec

/**
 * The order the arrival search tries columns in, which is the whole of what it costs.
 *
 * Every candidate is a full run of the generator's density functions, so reordering is the only free
 * optimisation available — and it is free only while the wide lattice stays a *subset* of the close one.
 * A step that stopped dividing would quietly add samples rather than reorder them, and nothing about the
 * arrival it produced would look wrong.
 */
class FootingCheck : StringSpec({

    "the wide lattice is a subset of the close one, so ordering adds no samples" {
        val columns = Ages.candidateColumns().toList()
        check(columns.size == columns.toSet().size) {
            "the search tries ${columns.size - columns.toSet().size} columns twice"
        }
    }

    "widely spaced columns are tried first, or distant land costs the whole lattice" {
        val columns = Ages.candidateColumns().toList()
        fun samplesToReach(distance: Int) =
            columns.indexOfFirst { (x, z) -> maxOf(kotlin.math.abs(x), kotlin.math.abs(z)) >= distance } + 1

        // Land at the far edge of the search is what used to cost sixteen seconds. Nearly all of the
        // lattice lies closer than it, so finding it must not mean sampling nearly all of the lattice.
        val far = samplesToReach(FAR)
        check(far in 1..<columns.size / 4) {
            "land $FAR blocks out is found after $far of ${columns.size} columns, which is not an ordering"
        }
    }

    "the origin is still tried first, so an Age with land underfoot costs one column" {
        check(Ages.candidateColumns().first() == 0 to 0) {
            "the search begins at ${Ages.candidateColumns().first()} rather than the origin"
        }
    }
}) {
    private companion object {
        /** The far edge of the search, in blocks. */
        const val FAR = 288
    }
}

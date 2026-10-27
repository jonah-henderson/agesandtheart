package co.voik.agesandtheart.age.resolution

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.Seam
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.language.ShippedLexicon.resolved
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** Landforms `and` joined meet as one country; rival ones keep the seam chance draws them. */
@Tags(NEEDS_REGISTRIES)
class ReconciledLandCheck : FunSpec({

    fun seamOf(row: String): Seam? = resolved(row, seed = SEED).composition.spreadOf(Aspect.TERRAIN).drawn

    test("two landforms joined by and are reconciled") {
        for (row in listOf("age gentle and sheer landmass", "age cratered and canyoned landmass")) {
            check(seamOf(row) == Seam.MERGED) { "'$row' drew ${seamOf(row)}" }
        }
    }

    test("two rival landforms are not") {
        val row = "age gentle sheer landmass"
        check(resolved(row, seed = SEED).composition.membersIn(Aspect.TERRAIN) == 2) { "'$row' did not divide" }
        check(seamOf(row) != Seam.MERGED) { "'$row' was reconciled" }
    }

    test("a landform with no ground to ease into another's is not, however it was joined") {
        val row = "age gentle and skyborne landmass"
        check(seamOf(row) != Seam.MERGED) { "'$row' was reconciled" }
    }
})

private const val SEED = 7L

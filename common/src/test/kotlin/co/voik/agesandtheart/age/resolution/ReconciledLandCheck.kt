package co.voik.agesandtheart.age.resolution

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.Seam
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.language.ShippedLexicon.resolved
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * Landforms `and` joined meet as one country where they can be eased, with a seam drawn round any that cannot; rival ones
 * keep the seam chance draws them; jumbled ones lie by chance.
 */
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

    test("a jumbled landmass lies where chance puts it, and an ordinary one where the land suits it") {
        fun isPatchwork(row: String) = resolved(row, seed = SEED).composition.anyMemberSays(Aspect.TERRAIN, Terrain.PATCHWORK)
        check(isPatchwork("age jumbled gentle and sheer landmass")) { "a jumbled landmass was laid out by the table" }
        check(!isPatchwork("age gentle and sheer landmass")) { "an ordinary landmass was jumbled" }
    }

    test("a landform with no ground to ease into another's is not, however it was joined") {
        val row = "age gentle and skyborne landmass"
        check(seamOf(row) != Seam.MERGED) { "'$row' was reconciled" }
    }

    test("the pillars, the cleft and the pyramids are eased too") {
        for (row in listOf("age gentle and pillared landmass", "age cleft and sheer landmass", "age pyramidal and eroded landmass")) {
            check(seamOf(row) == Seam.MERGED) { "'$row' drew ${seamOf(row)}" }
        }
    }

    test("what can be eased is, and a seam is drawn round what cannot") {
        val row = "age sheer and cleft and pillared and skyborne landmass"
        val spread = resolved(row, seed = SEED).composition.seamed(SEED).spreadOf(Aspect.TERRAIN)
        check(spread.drawn == Seam.MERGED) { "'$row' drew ${spread.drawn}" }
        check(spread.uneased != null && spread.uneased != Seam.MERGED && spread.uneased != Seam.FUZZED) {
            "'$row' drew ${spread.uneased} round the skyborne land"
        }
        val everyOneEases = resolved("age gentle and sheer landmass", seed = SEED).composition.seamed(SEED)
        check(everyOneEases.spreadOf(Aspect.TERRAIN).uneased == null) { "a merge with nothing left out drew a form for it" }
    }
})

private const val SEED = 7L

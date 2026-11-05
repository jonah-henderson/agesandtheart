package co.voik.agesandtheart.worldgen.structure

import io.kotest.core.spec.style.FunSpec

/**
 * A structure set spaced apart stands on a wider, more even grid, and one huddled on a tighter grid with nothing kept
 * between — always a grid vanilla would accept, the separation under the spacing.
 */
class StructureSpacingCheck : FunSpec({

    test("nought leaves vanilla's grid as it is") {
        check(StructureDensity.spacingApart(VILLAGE_SPACING, VILLAGE_SEPARATION, 0.0) == VILLAGE_SPACING to VILLAGE_SEPARATION) {
            "a village grid at nought became ${StructureDensity.spacingApart(VILLAGE_SPACING, VILLAGE_SEPARATION, 0.0)}"
        }
    }

    test("set apart, the grid widens and the separation takes most of it") {
        val (spacing, separation) = StructureDensity.spacingApart(VILLAGE_SPACING, VILLAGE_SEPARATION, 1.0)
        check(spacing == VILLAGE_SPACING * 2) { "the furthest apart a village grid goes is $spacing" }
        check(separation > spacing * 3 / 4) { "set furthest apart, villages may still stand $separation chunks close on $spacing" }
    }

    test("huddled, the grid tightens and nothing is kept between") {
        val (spacing, separation) = StructureDensity.spacingApart(VILLAGE_SPACING, VILLAGE_SEPARATION, -1.0)
        check(spacing < VILLAGE_SPACING) { "huddled villages stand on a grid of $spacing" }
        check(separation == 0) { "huddled villages keep $separation chunks between them" }
    }

    test("every grid is one vanilla accepts") {
        for (spacing in 2..80) for (separation in 0..<spacing) for (step in -10..10) {
            val (grid, apart) = StructureDensity.spacingApart(spacing, separation, step / 10.0)
            check(grid >= 2 && apart in 0..<grid) { "$spacing/$separation at ${step / 10.0} became $grid/$apart" }
        }
    }
}) {
    private companion object {
        const val VILLAGE_SPACING = 34
        const val VILLAGE_SEPARATION = 8
    }
}

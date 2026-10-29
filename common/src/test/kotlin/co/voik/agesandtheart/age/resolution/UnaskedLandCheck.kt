package co.voik.agesandtheart.age.resolution

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.language.ShippedLexicon.resolved
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** What an Age whose book names no landform stands on. */
@Tags(NEEDS_REGISTRIES)
class UnaskedLandCheck : FunSpec({

    test("an Age that names no landform stands on the overworld's rock or on four or more of the ordinary country") {
        val lands = SEEDS.map { seed -> resolved("age lush biomes", seed).composition.terrains }
        for (land in lands) {
            val isTheOverworld = land == listOf(Terrain.OVERWORLD)
            val isOrdinaryCountry = land.size >= LEAST && land.all(Terrain::standsUnasked)
            check(isTheOverworld || isOrdinaryCountry) { "an unasked Age stood on $land" }
        }
        val mixed = lands.count { it.size > 1 }
        check(mixed in SEEDS.count() / 4..SEEDS.count() * 3 / 4) { "$mixed of ${SEEDS.count()} unasked Ages were mixed" }
        check(lands.any { it.size > LEAST }) { "no unasked Age stood on more than $LEAST landforms" }
    }

    test("a named landform, or another world's rock, is kept") {
        for (seed in SEEDS) {
            check(resolved("age pillared landmass", seed).composition.terrains == listOf(Terrain.PILLARS))
            check(resolved("infernal age", seed).composition.terrains == listOf(Terrain.NETHER))
        }
    }

    test("a land steered but not named is a mixture of the ordinary country") {
        for (seed in SEEDS) {
            val land = resolved("age varied landmass", seed).composition.terrains
            check(land.size >= LEAST && land.all(Terrain::standsUnasked)) { "'varied landmass' stood on $land" }
        }
    }
})

private val SEEDS = 0L..<40L
private const val LEAST = 4

package co.voik.agesandtheart.generation

import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.age.aspect.Spawns
import co.voik.agesandtheart.age.aspect.Structures

/**
 * What an Age lets turn up where it does not belong — `incongruous features`, `inhabitants` and `structures`
 *. Handed to the generator as its feature and spawn policies are: rebuilt from
 * the recipe on every open, and never saved.
 */
data class Incongruity(
    /** Every biome's features in every biome, at any height — [co.voik.agesandtheart.worldgen.feature.FreeForAll]. */
    val features: Boolean = false,
    /** Every biome's creatures everywhere, past their own placement rules — [IncongruousSpawns]. */
    val spawns: Boolean = false,
    /** Structures in any biome and at any height — [IncongruousStructures]. */
    val structures: Boolean = false,
) {
    companion object {
        val NONE = Incongruity()

        fun of(composition: AgeComposition) = Incongruity(
            features = composition.optionsFor(Aspect.FEATURES).isTrue(Features.INCONGRUOUS),
            spawns = composition.optionsFor(Aspect.SPAWNS).isTrue(Spawns.INCONGRUOUS),
            structures = composition.optionsFor(Aspect.STRUCTURES).isTrue(Structures.INCONGRUOUS),
        )
    }
}

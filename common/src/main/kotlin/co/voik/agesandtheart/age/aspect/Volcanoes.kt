package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.location
import net.minecraft.resources.Identifier

/**
 * Whether an Age has volcanoes in it (design §7.1.2).
 *
 * **One fact in the recipe, read by three things.** The terrain raises the cones from it, the feature lays
 * the lava tubes in their calderas, and the danger evaluator scores it — so a writer who asks for
 * volcanoes gets all three, and none of them can disagree about whether the Age has any.
 *
 * It rides the features pool rather than being an aspect of its own: what a volcano *is* to a recipe is a
 * thing that gets placed, and the pool already carries a claim's confinement and rungs.
 */
object Volcanoes {

    /** The placed feature a writer names, and the id everything else keys on. */
    val ID: Identifier = "volcano".location()

    /**
     * Whether this composition asks for volcanoes.
     *
     * Read off the claim rather than the placed feature registry, so it answers the same before an Age is
     * opened as after — which is what lets the desk survey and `/age danger score` ask it of a recipe that
     * has never been built.
     */
    fun askedFor(composition: AgeComposition): Boolean =
        composition.optionsFor(Aspect.FEATURES, 0).claimsOn(Features.PLACES)
            .any { claim -> Identifier.tryParse(claim.value) == ID }
}

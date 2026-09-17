package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.location
import net.minecraft.resources.Identifier

/**
 * Whether an Age has volcanoes in it (design §7.1.2) — the **mountains**, and only those.
 *
 * **One fact in the recipe, read by three things.** The terrain raises the cones from it, the vent feature
 * seats tubes in the crater lakes they arrive full of, and the danger evaluator scores it — so a writer
 * who asks for volcanoes gets all of that, and none of the three can disagree about whether the Age has
 * any.
 *
 * **What a volcano no longer drags in with it** is the small craters (`agesandtheart:firespout`), the deep magma
 * ([MagmaChambers]) and the tubes seeded through the rock (`agesandtheart:lava_tubes`, which needs no
 * object here — its whole expression is a placed feature the claim resolves to). Each is written for on
 * its own, and `volcanic` is the word that reaches all four. Jonah, walking the split on 2026-09-11:
 * *"it feels a little counterintuitive to write volcanoes and get volcanoes + something else."*
 *
 * It rides the features pool rather than being an aspect of its own: what a volcano *is* to a recipe is a
 * thing that gets placed, and the pool already carries a claim's confinement and rungs.
 */
object Volcanoes {

    /** The placed feature a writer names, and the id everything else keys on. */
    val ID: Identifier = "volcano".location()

    /**
     * **How many of them this composition asks for**, or null where it asks for none.
     *
     * The amount and not merely the fact. This answered a plain `Boolean` until 2026-09-11, so a quantifier
     * reached the vents and the buried tubes and never the mountains — `teeming volcano` raised exactly as
     * many cones as `volcano` did, and put four times the vents in them. See
     * [co.voik.agesandtheart.worldgen.cellFor].
     */
    fun amountIn(composition: AgeComposition): Double? = Features.claimNaming(composition, ID)?.density
}

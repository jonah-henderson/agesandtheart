package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.location
import net.minecraft.resources.Identifier

/**
 * Whether an Age has magma chambers under it (design §7.1.2) — the deep hollows with lava standing in
 * their bottoms, and the vents seated in those pools.
 *
 * **Written for on its own, and it needs no volcano over it.** Deep magma with nothing on the surface to
 * say so is a world worth writing: what you find is decided by where you dig rather than by what you can
 * see from a hilltop, and a chamber opened by a carver is a lava lake in a cavern while the same chamber
 * sealed in rock is what floods the tunnel a pick reaches it through. `volcanic` is the word that asks for
 * these and the mountains together.
 *
 * It rides the features pool for the reason [Volcanoes] does — the chambers are terrain, but what a recipe
 * holds is a claim on a thing that gets placed, and the vents in them are exactly that.
 */
object MagmaChambers {

    /** The placed feature a writer names, and the id everything else keys on. */
    val ID: Identifier = "magma_chamber".location()

    /** Whether this composition asks for them — the terrain's question, which has no amount in it. */
    fun askedFor(composition: AgeComposition): Boolean = Features.claimNaming(composition, ID) != null
}

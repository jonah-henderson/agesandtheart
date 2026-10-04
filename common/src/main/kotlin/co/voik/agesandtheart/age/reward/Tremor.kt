package co.voik.agesandtheart.age.reward

import co.voik.agesandtheart.age.Instability
import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Price
import co.voik.agesandtheart.age.Spending
import net.minecraft.server.MinecraftServer

/**
 * Whether the ground will hold — the seismograph, read at the desk over a sentence nobody has written yet
 * (design §7.3).
 *
 * **It reads what the instability will *buy*, not how incoherent the sentence is**, and that is the whole
 * of what separates it from the grammar guide's contradictions. The guide names them; this says whether they
 * will do anything. An Age can be at odds with itself and still stand, because a budget too small to afford
 * any manifestation buys none — and a writer deserves to know that the flaws the guide is marking are flaws
 * the world will absorb.
 *
 * **Derived from the same [Spending] the generator uses**, so the instrument and the world cannot drift
 * apart. That is [Yield.forVeins]'s rule applied to the other register: report the thing that is actually
 * placed, never a second calculation that agrees with it today.
 */
data class Tremor(val footing: Footing, val manifests: List<Manifestation>) {

    companion object {
        /** What the ground under this sentence will do, on the server about to be asked to build it. */
        fun of(server: MinecraftServer, instability: Instability, seed: Long): Tremor =
            of(instability, seed, Price.list(server))

        /** The same, against a price list handed in — offline, and what the checks use. */
        fun of(instability: Instability, seed: Long, prices: Map<Manifestation, Price>): Tremor {
            val spending = Spending.of(instability.index, seed, prices)
            val manifests = Manifestation.entries.filter { spending.bought(it) > 0 }
            return Tremor(Footing.of(manifests), manifests)
        }
    }
}

/**
 * The three states an Age's ground can read as (design §3.2 — no number ever reaches a player).
 *
 * **Three because three is what can be seen at a glance** (Jonah, 2026-09-07): the asset pass gives the
 * instrument an animation per state, so a writer who has learned the shapes never has to read the words.
 * Anything finer would be a dial nobody could tell apart across a room.
 */
enum class Footing(val key: String) {
    /** Nothing manifests. Either the sentence is coherent, or its flaws cannot afford to become anything. */
    STABLE("stable"),

    /** The Age comes apart in some way short of the floor going. */
    UNSTABLE("unstable"),

    /** [Manifestation.COLLAPSE] is bought, which is the one that takes the ground itself. */
    COLLAPSING("collapsing"),
    ;

    companion object {
        fun of(manifests: List<Manifestation>): Footing = when {
            Manifestation.COLLAPSE in manifests -> COLLAPSING
            manifests.isEmpty() -> STABLE
            else -> UNSTABLE
        }
    }
}

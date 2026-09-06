package co.voik.agesandtheart.age.reward

import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.Instability
import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Price
import co.voik.agesandtheart.age.Spending
import net.minecraft.server.MinecraftServer

/**
 * What an Age is likely to hold and roughly how much (design §7.7) — the geologist's tools, read at the
 * desk over a sentence nobody has written yet.
 *
 * **Quantities and names, never a danger forecast.** Saying what the danger *was* would be a preview and
 * would move §7.5's line; saying what comes out of the ground is an outcome, and it survives an evocative
 * word that the writer themselves cannot unpack. The lesson — that the Ages surveying well are the ones
 * with hazards written into them — is left to be noticed rather than told.
 *
 * **[deposit] carries no number and [earlyMaterials] carries no amount**, which is the split §7.7 asks
 * for: the danger material is one name, so all of its information is in how much there is, where the early
 * materials are several and a name a writer does not recognise is already a lure.
 */
data class Survey(val deposit: Yield, val earlyMaterials: Set<EarlyGameRareMaterial>) {

    companion object {
        /** What the Age this composition describes would hold, on the server about to be asked to build it. */
        fun of(
            server: MinecraftServer,
            composition: AgeComposition,
            instability: Instability,
            seed: Long,
        ): Survey = of(composition, instability, seed, DangerTable.of(server), Price.list(server))

        /** The same, against a table and a price list handed in — offline, and what the checks use. */
        fun of(
            composition: AgeComposition,
            instability: Instability,
            seed: Long,
            table: DangerTable,
            prices: Map<Manifestation, Price>,
        ): Survey {
            // Surveyed at a desk by somebody about to write the book, which is the whole of what `authored`
            // asks. A found Age is never surveyed, because a found Age was never composed here.
            val danger = Danger.of(composition, instability, seed, authored = true, table, prices)
            val spending = Spending.of(instability.index, prices, seed)
            return Survey(
                deposit = Yield.forVeins(Deposits.veinsPerChunk(danger)),
                earlyMaterials = EarlyGameRareMaterials.grownIn(composition, spending, prices),
            )
        }
    }
}

/**
 * How much of a material an Age holds, as a word (design §3.2 — no number ever reaches a player).
 *
 * Banded on **veins per chunk**, which is what [Deposits] actually places, so the report and the ground
 * cannot drift apart. The bands are wide at the top because there is no ceiling: a terminal Age multiplies
 * its yield tenfold and lands in [IMMENSE] however marginal its score was.
 */
enum class Yield(val key: String, val fewestVeins: Int) {
    NONE("none", 0),
    TRACE("trace", 1),
    LITTLE("little", 3),
    SOME("some", 5),
    MUCH("much", 9),
    IMMENSE("immense", 20),
    ;

    companion object {
        fun forVeins(veins: Int): Yield = entries.last { veins >= it.fewestVeins }
    }
}

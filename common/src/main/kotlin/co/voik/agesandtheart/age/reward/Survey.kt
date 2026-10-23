package co.voik.agesandtheart.age.reward

import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.AgeTemplate
import co.voik.agesandtheart.age.Instability
import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Ladder
import co.voik.agesandtheart.age.SpendingSetting
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
        /**
         * What the Age this composition describes would hold, on the server about to be asked to build it. [authored]
         * is whether a player wrote it, which is what a deposit is paid for: a sentence at a desk is about to be,
         * and an Age read where it stands was or was not.
         */
        fun of(
            server: MinecraftServer,
            composition: AgeComposition,
            template: AgeTemplate,
            instability: Instability,
            seed: Long,
            authored: Boolean = true,
        ): Survey = of(
            composition,
            instability,
            seed,
            DangerTable.of(server),
            Ladder.list(server),
            SpendingSetting.of(server, composition, template, seed),
            authored,
        )

        /** The same, against a table and the ladders handed in — offline, and what the checks use. */
        fun of(
            composition: AgeComposition,
            instability: Instability,
            seed: Long,
            table: DangerTable,
            ladders: Map<Manifestation, Ladder>,
            setting: SpendingSetting = SpendingSetting.of(composition),
            authored: Boolean = true,
        ): Survey {
            val danger = Danger.of(composition, instability, seed, authored, table, ladders, setting)
            val spending = Spending.of(instability, seed, ladders, setting)
            return Survey(
                deposit = Yield.of(danger),
                earlyMaterials = EarlyGameRareMaterials.grownIn(composition, seed, spending),
            )
        }
    }
}

/**
 * How much of a material an Age holds, as a word (design §3.2 — no number ever reaches a player), each laid as a
 * vanilla ore is ([Deposits]): traces as emerald, a little as diamond, some as iron, a good deal between iron and
 * copper, large amounts as copper, and massive layers in an Age that collapses, whatever it scored.
 *
 * [fromScore] is the least danger an Age reads at one: the old banding on veins per chunk, six to a full score.
 */
enum class Yield(val key: String, val fromScore: Double) {
    NONE("none", 0.0),
    TRACE("trace", 0.0),
    LITTLE("little", 2.5 / SIXTHS),
    SOME("some", 4.5 / SIXTHS),
    MUCH("much", 8.5 / SIXTHS),
    IMMENSE("immense", 19.5 / SIXTHS),
    MASSIVE("massive", Double.POSITIVE_INFINITY),
    ;

    companion object {
        /** What [danger] pays: nothing where it does not, the hoard where it collapses, and otherwise by its score. */
        fun of(danger: Danger): Yield = when {
            !danger.paysOut -> NONE
            danger.isTerminal -> MASSIVE
            else -> entries.last { it != MASSIVE && danger.score >= it.fromScore }
        }
    }
}

/** The steps a full score of danger was divided into, which is where the bands' lines fall. */
private const val SIXTHS = 6.0

package co.voik.agesandtheart.age.reward

import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.AgeWorld
import co.voik.agesandtheart.age.Instability
import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Price
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Claim
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Phenomena
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.aspect.Share
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.agesandtheart.age.aspect.Spawns
import co.voik.agesandtheart.age.aspect.Surface
import co.voik.agesandtheart.age.aspect.Terrain
import net.minecraft.server.MinecraftServer

/**
 * How dangerous a written Age is — the danger half of §7.7's one evaluator.
 *
 * **Read from the recipe, never measured in play**, because the geologic survey is read at the desk before
 * the Age exists. That is also what makes this and the D'ni signature one evaluator with two clients: both
 * ask the same question of the same resolved composition.
 *
 * **Danger and instability are separate axes** (§7.7). A fully coherent Age can be a dangerous one, and a
 * contradiction pays only where the index actually *bought a hazard* — so nothing here reads
 * [co.voik.agesandtheart.age.Instability.index]. It reads the hazards, and what an unstable Age's budget
 * bought is folded into the contributor it physically belongs to: an inflicted sandfall is a phenomenon,
 * and wounds are hostile-spawn pressure.
 *
 * **The one rule that must not be broken is the averaging.** A five-percent magma region must not score
 * like a magma world, or writing yourself a safe base in the corner of a hostile Age costs nothing and
 * pays in full. So the spatial contributor is a share-weighted mean and never a maximum, and §7.7's
 * "ingenuity worth rewarding is solving the problem inside the Age, not at the writing desk" survives.
 *
 * **The template needs no special case.** What a template supplied is already merged into the composition
 * a recipe holds ([co.voik.agesandtheart.age.word.Resolution]), so an infernal Age arrives here with a
 * lava sea and a sealed sky written down exactly as if a writer had asked for both.
 */
data class Danger(
    /** What the Age is made of: its rock, its sea and its surface, averaged over the ground each covers. */
    val materials: Double,
    /** The hostile population the book asked for, plus what its wounds will draw. */
    val spawns: Double,
    /** The processes it runs, written and inflicted alike. */
    val phenomena: Double,
    /** How dark it is, and only insofar as that drives mob pressure. */
    val lighting: Double,
    /**
     * How far into [Manifestation.COLLAPSE] the Age went, from nothing to everything it could buy.
     *
     * **Deliberately not part of [score].** §7.7 turns the rewards up to absurd amounts where an Age will
     * not last, but whether that multiplier keys on reaching collapse at all or on how far into it is an
     * open question (§7.7, "Still open"). This is the number either answer reads, exposed so the question
     * can be settled without reopening the evaluator.
     */
    val terminal: Double,
    /** Whether a player wrote this Age. A found book's Age never pays, whatever it scores. */
    val authored: Boolean,
    private val weights: DangerTable.Weights,
    private val paysAbove: Double,
) {
    /**
     * The one number §7.7 asks for.
     *
     * **No ceiling**, because ingenuity is the point: an Age asking for more hostile life than
     * [DangerTable.spawnsFull] calls a full population scores over one, and a player who works out how to
     * survive it has earned what is in it.
     */
    val score: Double
        get() = materials * weights.materials +
            spawns * weights.spawns +
            phenomena * weights.phenomena +
            lighting * weights.lighting

    /**
     * Whether this Age pays at all — the threshold, **and the provenance flag with it**.
     *
     * One question rather than two, so that no caller can read the score and forget who wrote the book.
     */
    val paysOut: Boolean get() = authored && score >= paysAbove

    /**
     * Whether an Age this dangerous may hold D'ni ruins — the same threshold read the other way.
     *
     * §7.7: the two rewards partition by one number. Below it an Age may hold a city; above it, deposits.
     * Simultaneous ruins and deposits are not a design goal.
     */
    val allowsRuins: Boolean get() = score < paysAbove

    /**
     * Whether this Age will not last, and its rewards are turned up to absurd amounts (§7.7).
     *
     * **The trigger is a full reach rather than a slope** (Jonah, 2026-09-05): the Age has bought every
     * step of collapse there is to buy, which is the top of the instability ladder as it currently stands.
     * Deliberately a threshold and deliberately the highest one — the raid is meant to be the Age nobody
     * could have lived in, not a bonus that creeps in as an Age gets worse. Expected to be relaxed once
     * there is play behind it.
     */
    val isTerminal: Boolean get() = terminal >= EVERY_STEP_OF_IT

    override fun toString(): String =
        "danger %.3f (materials %.3f, spawns %.3f, phenomena %.3f, lighting %.3f)"
            .format(score, materials, spawns, phenomena, lighting)

    companion object {
        /** A collapse bought as far as it goes — see [isTerminal]. */
        private const val EVERY_STEP_OF_IT = 1.0

        /** How dangerous the Age [recipe] describes is, on the server running it. */
        fun of(server: MinecraftServer, recipe: AgeRecipe): Danger =
            of(recipe, DangerTable.of(server), Price.list(server))

        /**
         * The same, against a table and a price list handed in — offline, and what the checks use.
         *
         * A bespoke Age scores nothing: it has no composition to read, and none of them is a thing a
         * writer wrote.
         */
        fun of(recipe: AgeRecipe, table: DangerTable, prices: Map<Manifestation, Price>): Danger {
            val composition = (recipe.world as? AgeWorld.Composed)?.composition
                ?: return nothing(table, recipe.authored)
            return of(composition, recipe.instability, recipe.seed, recipe.authored, table, prices)
        }

        /**
         * How dangerous a composition is, without an Age having been written from it yet.
         *
         * **The entry the desk needs**, and the reason the scoring does not simply take a recipe: §7.7's
         * survey is read *at the desk before the Age exists*, where a writer has a resolved composition
         * and no recipe at all.
         */
        fun of(
            composition: AgeComposition,
            instability: Instability,
            seed: Long,
            authored: Boolean,
            table: DangerTable,
            prices: Map<Manifestation, Price>,
        ): Danger {
            val spent = Spending.of(instability.index, prices, seed)
            return Danger(
                materials = materialsOf(composition, table),
                spawns = spawnsOf(composition, table, spent, prices),
                phenomena = phenomenaOf(composition, table, spent, prices),
                lighting = lightingOf(composition, table),
                terminal = spent.reach(Manifestation.COLLAPSE, prices),
                authored = authored,
                weights = table.weights,
                paysAbove = table.paysAbove,
            )
        }

        private fun nothing(table: DangerTable, authored: Boolean) = Danger(
            materials = 0.0,
            spawns = 0.0,
            phenomena = 0.0,
            lighting = 0.0,
            terminal = 0.0,
            authored = authored,
            weights = table.weights,
            paysAbove = table.paysAbove,
        )

        /**
         * What the Age is made of, averaged over the ground each territory covers.
         *
         * **The worst of the three, not their sum.** The rock, the sea and the surface are three answers to
         * one question — *what is the stuff here* — and a lava sea standing over magma ground is one
         * hazard seen twice rather than two. Summing them would also let an Age creep over the threshold
         * on three harmless materials, which is the presence-based scoring §7.7 forbids wearing a
         * different hat.
         *
         * **The surface is one value, not a division.** It divides in principle and does not yet — see
         * [Aspect.spatial] — so it is read at member zero and weighted as covering the whole Age.
         */
        private fun materialsOf(composition: AgeComposition, table: DangerTable): Double {
            val rock = shareWeighted(composition, Aspect.TERRAIN) { member ->
                worstOf(composition.optionsFor(Aspect.TERRAIN, member).allOf(Terrain.STONE), table)
            }
            val sea = shareWeighted(composition, Aspect.SEA) { member ->
                composition.seas.getOrNull(member)
                    ?.takeUnless { it.isEmpty }
                    ?.let { table.material(it.id.toString()) }
                    ?: 0.0
            }
            val skin = worstOf(composition.optionsFor(Aspect.SURFACE, 0).allOf(Surface.MATERIAL), table)
            return maxOf(rock, sea, skin)
        }

        /**
         * The worst of several materials mingled into one territory.
         *
         * Mingled materials are laid together through the whole of the ground they cover
         * ([Terrain.MINGLING]), so there is nowhere in the territory the dangerous one is not — which is
         * exactly the case where an average would be wrong and a maximum is right.
         */
        private fun worstOf(named: List<String>, table: DangerTable): Double = named
            .filter { it != Parameter.UNCHANGED }
            .maxOfOrNull(table::material)
            ?: 0.0

        /**
         * The hostile population the book asked for, plus the pressure its wounds will draw.
         *
         * **Zero is whatever the biomes would have held.** An Age nobody said anything about the life of
         * still has vanilla's monsters in it, and if that scored, every Age would pay — so this measures
         * only what a book *asked for*, which is §7.6's "the criteria must select for something the
         * default Age is not" applied to the other pole.
         *
         * **The raw claims rather than a [co.voik.agesandtheart.age.aspect.Skew].** `Skew.of(claims)` with
         * no biome drops every claim confined to one, which is right for a generator asking what lives in
         * a particular place and wrong here: a Age that asked for ghasts in one biome did ask for ghasts,
         * and the score wants them at [DangerTable.confinedWeight] rather than not at all.
         */
        private fun spawnsOf(
            composition: AgeComposition,
            table: DangerTable,
            spent: Spending,
            prices: Map<Manifestation, Price>,
        ): Double {
            val claims = composition.optionsFor(Aspect.SPAWNS, 0).claimsOn(Spawns.LIVES)
            val asked = wanted(claims).sumOf { claim ->
                table.spawn(claim.value) * claim.density * confinement(claim, table)
            }
            // **Wounds are hostile-spawn pressure, and that is what they physically are**: `Hostility`
            // makes what comes nastier and makes more of it come, and both registers are the population.
            //
            // **`WORSENING_WOUNDS` is deliberately not counted.** It buys how fast the wound density
            // climbs with the Age's *own days*, and this is read at the desk before the Age has any — so
            // at the only moment the score is asked for, worsening has done nothing. Scoring it here would
            // be measuring in play, which §7.7 says this never does.
            val wounds = spent.reach(Manifestation.WOUNDS, prices) * table.woundHostility
            return (asked / table.spawnsFull) + wounds
        }

        /**
         * What happens here — written and inflicted alike, and where they meet they compound.
         *
         * The written half is [Phenomena.claimsIn], which is the same reading the generator uses. The
         * inflicted half is [Phenomenon.inflictedBy]: an Age whose contradiction bought a sandfall is
         * running one whether or not its book asked, and §7.7 is explicit that this is where a
         * contradiction earns its keep.
         */
        private fun phenomenaOf(
            composition: AgeComposition,
            table: DangerTable,
            spent: Spending,
            prices: Map<Manifestation, Price>,
        ): Double {
            val options = composition.optionsFor(Aspect.PHENOMENA, 0)
            val written = Phenomena.claimsIn(options).sumOf { claim ->
                table.phenomenon(claim.value) * claim.density * confinement(claim, table)
            }
            val inflicted = Phenomenon.entries.sumOf { phenomenon ->
                val manifestation = phenomenon.inflictedBy ?: return@sumOf 0.0
                table.phenomenon(phenomenon.key) * spent.reach(manifestation, prices)
            }
            return (written + inflicted) / table.phenomenaFull
        }

        /**
         * How dark it is — **and nothing where nothing walks**.
         *
         * §7.7 counts lighting "only insofar as it drives mob pressure: a sealed sky is danger because of
         * what walks under it". So an Age that spawns nothing at all takes no danger from its roof, which
         * is the sentence read literally rather than a special case bolted on.
         */
        private fun lightingOf(composition: AgeComposition, table: DangerTable): Double {
            if (spawnsNothing(composition)) return 0.0
            val sky = composition.optionsFor(Aspect.SKY, 0)
            val sun = composition.optionsFor(Aspect.SUN, 0)
            return when {
                Sky.isRoofed(sky) -> table.sealed
                Sky.isLightless(sky, sun) -> table.lightless
                else -> 0.0
            }
        }

        /** Whether the book said nothing lives here at all — `lives=nothing`. */
        private fun spawnsNothing(composition: AgeComposition): Boolean =
            Spawns.NOTHING in composition.optionsFor(Aspect.SPAWNS, 0).allOf(Spawns.LIVES)

        /** The claims that ask for something, removals applied last exactly as [Skew] applies them (§3.5). */
        private fun wanted(claims: List<Claim>): List<Claim> {
            val struck = claims.filter { it.polarity == Polarity.EXCEPT }.map { it.value }.toSet()
            return claims
                .filter { it.polarity != Polarity.EXCEPT }
                .distinctBy { it.value }
                .filterNot { it.value in struck }
        }

        /** What a claim is worth for the ground it covers: all of it, or one biome's worth of it. */
        private fun confinement(claim: Claim, table: DangerTable): Double =
            if (claim.confinedTo == null) 1.0 else table.confinedWeight

        /**
         * [rate] over each of [aspect]'s territories, weighted by the ground each covers.
         *
         * Shares are relative to the widest ([Share]), so they are normalised here into fractions of the
         * world — which is what makes this an average rather than a sum, and is the whole of the rule that
         * a five-percent magma region must not score like a magma world.
         */
        private fun shareWeighted(
            composition: AgeComposition,
            aspect: Aspect,
            rate: (Int) -> Double,
        ): Double {
            val shares = composition.spreadOf(aspect).shares
            val whole = shares.sum()
            if (whole <= 0.0) return 0.0
            return shares.withIndex().sumOf { (member, share) -> rate(member) * share } / whole
        }
    }
}

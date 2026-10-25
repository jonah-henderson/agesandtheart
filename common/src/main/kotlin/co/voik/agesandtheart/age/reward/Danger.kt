package co.voik.agesandtheart.age.reward

import co.voik.agesandtheart.age.phenomena.Happenings
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.AgeWorld
import co.voik.agesandtheart.age.Instability
import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Ladder
import co.voik.agesandtheart.age.SpendingSetting
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Atmosphere
import co.voik.agesandtheart.age.aspect.Gravity
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Claim
import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.age.aspect.Skew
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Phenomena
import co.voik.agesandtheart.age.aspect.Pool
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.aspect.Rung
import co.voik.agesandtheart.age.aspect.Share
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.agesandtheart.age.aspect.Spawns
import co.voik.agesandtheart.age.aspect.Surface
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.consequence.StrangeGravity
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.MobCategory
import kotlin.math.pow

/**
 * How dangerous a written Age is (design §7.7): every hazard it holds, each on [DangerTable]'s one scale, and
 * [score], which is them combined.
 *
 * **Read from the recipe, never measured in play**, because the survey is read at the desk before the Age exists.
 * **Danger and instability are separate axes**: nothing here reads the instability index, only what its budget
 * bought — an inflicted sandfall is a sandfall, and wounds are wounds.
 *
 * **What covers part of an Age scores the part it covers.** A five-percent magma region must not score like a magma
 * world, or a safe base written into the corner of a hostile Age costs nothing and pays in full.
 */
data class Danger(
    /** Every hazard the Age holds, worth more than nothing. */
    val hazards: List<Hazard>,
    /**
     * How far into [Manifestation.COLLAPSE] the Age went, from nothing to everything it could buy. Not part of
     * [score]: an Age that will not last pays whatever it scores.
     */
    val terminal: Double,
    private val table: DangerTable,
) {
    /**
     * The hazards combined: the [DangerTable.combining]-th root of the sum of each raised to that power.
     *
     * **Neither their sum nor their worst.** Two hazards a player has to answer separately are more than either, and
     * less than both added, which is how the combinations came out when they were rated together.
     */
    val score: Double
        get() {
            val exponent = table.combining
            return hazards.sumOf { it.value.pow(exponent) }.pow(1.0 / exponent)
        }

    /** Whether this Age pays at all — over the line, or coming apart, which buys the hoard whatever else it scores. */
    val paysOut: Boolean get() = score >= table.lines.paysAt || isTerminal

    /** Whether an Age this dangerous may hold D'ni ruins: under its own line, and lasting. */
    val allowsRuins: Boolean get() = score < table.lines.ruinsBelow && !isTerminal

    /** Whether this Age will not last, and its rewards are turned up to absurd amounts: any collapse bought. */
    val isTerminal: Boolean get() = terminal > NOTHING

    /** The least score at which this Age's deposit reads as [yield]. */
    fun lineFor(yield: Yield): Double = table.lines.from(yield)

    /** The hazards from [source]. */
    fun from(source: Source): List<Hazard> = hazards.filter { it.source == source }

    override fun toString(): String =
        "danger %.3f (%s)".format(score, hazards.joinToString { "%s %.3f".format(it.name, it.value) })

    /** One thing that makes the Age more dangerous than the overworld, and how much. */
    data class Hazard(val source: Source, val name: String, val value: Double)

    /** Where a hazard comes from, which is what tells a readout and a check which part of the Age to look at. */
    enum class Source { GROUND, SEA, VOID, UNDERGROUND, CREATURES, PHENOMENON, FEATURE, CONDITION, GRAVITY, WOUNDS }

    companion object {
        private const val NOTHING = 0.0
        private const val AS_THEY_ARE = 1.0

        /** The monster whose hardiness stands for the hardiness of the biomes' own, where darkness lets them out. */
        private const val ORDINARY_MONSTER = "minecraft:zombie"

        /** The creatures a charge does anything to. */
        private val CHARGEABLE = setOf("minecraft:creeper")

        /** How dangerous the Age [recipe] describes is, on the server running it. */
        fun of(server: MinecraftServer, recipe: AgeRecipe): Danger = of(
            recipe,
            DangerTable.of(server),
            Ladder.list(server),
            SpendingSetting.of(server, recipe.composition, recipe.template, recipe.seed),
        )

        /** The same, against a table and the ladders handed in. A bespoke Age, having no composition, scores nothing. */
        fun of(
            recipe: AgeRecipe,
            table: DangerTable,
            ladders: Map<Manifestation, Ladder>,
            setting: SpendingSetting = SpendingSetting.of(recipe.composition),
        ): Danger {
            val composition = (recipe.world as? AgeWorld.Composed)?.composition
                ?: return Danger(emptyList(), terminal = NOTHING, table)
            return of(composition, recipe.instability, recipe.seed, table, ladders, setting)
        }

        /** How dangerous a composition is before an Age has been written from it — what the desk asks. */
        fun of(
            composition: AgeComposition,
            instability: Instability,
            seed: Long,
            table: DangerTable,
            ladders: Map<Manifestation, Ladder>,
            setting: SpendingSetting = SpendingSetting.of(composition),
        ): Danger {
            val spent = Spending.of(instability, seed, ladders, setting)
            val hazards = buildList {
                add(Hazard(Source.GROUND, "ground", groundOf(composition, table)))
                add(Hazard(Source.SEA, "sea", seaOf(composition, table)))
                if (EarlyGameRareMaterials.holdsAnAbyss(composition, seed)) add(Hazard(Source.SEA, "abyss", table.ground.abyss))
                add(Hazard(Source.VOID, "void", voidOf(composition, table)))
                add(Hazard(Source.GROUND, "landform", landformsOf(composition, table)))
                add(Hazard(Source.UNDERGROUND, composition.underground.key, table.ground.underground(composition.underground.key)))
                add(Hazard(Source.CREATURES, "monsters", creaturesOf(composition, table, seed)))
                addAll(phenomenaOf(composition, table, spent, seed))
                addAll(featuresOf(composition, table))
                addAll(conditionsOf(composition, table, seed))
                add(Hazard(Source.GRAVITY, "gravity", gravityOf(composition, table, spent, seed)))
                add(Hazard(Source.WOUNDS, "wounds", spent.reach(Manifestation.WOUNDS) * table.wounds))
            }
            return Danger(hazards.filter { it.value > NOTHING }, terminal = spent.reach(Manifestation.COLLAPSE), table)
        }

        /**
         * What the Age stands on: the worst of its rock and its surface, each over the ground it covers. Materials
         * mingled into one territory are laid through the whole of it, so there the worst of them counts.
         */
        private fun groundOf(composition: AgeComposition, table: DangerTable): Double {
            fun worstOf(named: List<String>) =
                named.filter { it != Parameter.UNCHANGED }.maxOfOrNull(table.ground::madeOf) ?: NOTHING
            val rock = shareWeighted(composition, Aspect.TERRAIN) { member ->
                worstOf(composition.optionsFor(Aspect.TERRAIN, member).allOf(Terrain.STONE))
            }
            val skin = worstOf(composition.optionsFor(Aspect.SURFACE, 0).allOf(Surface.MATERIAL))
            return maxOf(rock, skin)
        }

        private fun seaOf(composition: AgeComposition, table: DangerTable): Double =
            shareWeighted(composition, Aspect.SEA) { member ->
                composition.seas.getOrNull(member)?.takeUnless { it.isEmpty }?.let { table.ground.sea(it.id.toString()) }
                    ?: NOTHING
            }

        /** Land hanging over nothing, over the ground it covers — and nothing where a sea lies beneath to fall into. */
        private fun voidOf(composition: AgeComposition, table: DangerTable): Double {
            val hasASeaBeneath = composition.seas.any { !it.isEmpty }
            if (hasASeaBeneath) return NOTHING
            return shareWeighted(composition, Aspect.TERRAIN) { member ->
                composition.terrains.getOrNull(member)?.let { table.ground.void(it.key) } ?: NOTHING
            }
        }

        /** Landforms dangerous in themselves, over the ground they cover — the Nether's, with its own crowd. */
        private fun landformsOf(composition: AgeComposition, table: DangerTable): Double =
            shareWeighted(composition, Aspect.TERRAIN) { member ->
                composition.terrains.getOrNull(member)?.let { table.ground.landform(it.key) } ?: NOTHING
            }

        /**
         * The monsters an Age holds over the biomes' own — **one hazard, and the worst of what feeds it**.
         *
         * More monsters, monsters let out by darkness at every hour, and the biomes' own made harder are three ways of
         * meeting the same danger, and rated together they came out at the worst of them rather than their sum. Each
         * is scaled by the hardiness of what it brings, and the whole by the monster cap the book set.
         *
         * **A claim's amount is a weight among its kinds, not a crowd**, so it is not read here. A kind named outright is
         * worth its own rating everywhere; kinds described (`monsters`, a mood's lean) are more of what already grows,
         * worth an ordinary monster once they reach [A_CROWD_OF_MONSTERS] of all monsters, and that share of it short of
         * that.
         *
         * **A kind loosed from its own rules** (`incongruous inhabitants`) is worth more as it was asked for or made
         * harder; the biomes' own, loosed, are what the darkness lets out, so they are not loosed twice.
         */
        private fun creaturesOf(composition: AgeComposition, table: DangerTable, seed: Long): Double {
            if (spawnsNothing(composition)) return NOTHING
            val spawns = composition.optionsFor(Aspect.SPAWNS, 0)
            val claims = wanted(spawns.claimsOn(Spawns.LIVES))
            val loosed = if (spawns.isTrue(Spawns.INCONGRUOUS)) table.hardiness.loosed else AS_THEY_ARE
            fun hardiness(member: String) = hardinessOf(spawns, table.hardiness, seed, member)
            fun wasNamed(claim: Claim) = !claim.onlyWhereItGrows || claim.introducedIfAbsent
            val (named, described) = claims.partition(::wasNamed)
            val namedWorst = named.maxOfOrNull { claim ->
                table.creature(claim.value) * confinement(claim, table) * hardiness(claim.value)
            } ?: NOTHING
            val describedMonsters = described.filter { table.isAMonster(it.value) }
            val describedShare = describedMonsters.size.toDouble() / MONSTER_KINDS.coerceAtLeast(1)
            val describedWorth = table.creature(ORDINARY_MONSTER) * (describedShare / A_CROWD_OF_MONSTERS).coerceAtMost(AS_THEY_ARE) *
                (describedMonsters.maxOfOrNull { hardiness(it.value) } ?: AS_THEY_ARE)
            val crowd = Spawns.capsOf(spawns)[MobCategory.MONSTER] ?: AS_THEY_ARE
            val askedFor = maxOf(namedWorst, describedWorth) * loosed * crowd
            val letOut = darknessOf(composition, table.darkness) * hardiness(ORDINARY_MONSTER) * crowd
            val asked = claims.map(Claim::value).toSet()
            val madeHarder = madeHardier(spawns).filterNot { it in asked }.maxOfOrNull { member ->
                table.creature(member) * (hardiness(member) * loosed - AS_THEY_ARE)
            } ?: NOTHING
            return maxOf(askedFor, letOut, madeHarder, NOTHING)
        }

        /**
         * The share of all kinds of monster a described crowd reaches to be a crowd of monsters: half, since many kinds
         * never spawn of their own accord and `monsters` itself reaches only some four in five.
         */
        private const val A_CROWD_OF_MONSTERS = 0.5

        /** How many kinds of monster there are, against which a described crowd's reach is measured. */
        private val MONSTER_KINDS: Int by lazy {
            BuiltInRegistries.ENTITY_TYPE.count { it.category == MobCategory.MONSTER }
        }

        /**
         * How far the Age lets the monsters already here out at every hour: the worst of its sky and its rules, over the
         * ground whose own monsters are the overworld's. The Nether's and the End's own are rated with their landforms.
         */
        private fun darknessOf(composition: AgeComposition, darkness: DangerTable.Darkness): Double {
            val sky = when {
                composition.roofedByItsRock -> darkness.roofedByRock
                Sky.isRoofed(composition) -> darkness.sealed
                Sky.isLightless(composition) -> darkness.sunless
                else -> NOTHING
            }
            val loosed = composition.optionsFor(Aspect.SPAWNS, 0).isTrue(Spawns.INCONGRUOUS)
            val overworldly = shareWeighted(composition, Aspect.TERRAIN) { member ->
                if (composition.terrains.getOrNull(member) in RATED_WITH_THEIR_OWN) NOTHING else AS_THEY_ARE
            }
            return maxOf(sky, if (loosed) darkness.incongruous else NOTHING) * overworldly
        }

        /** The vanilla landforms whose own population and dark come with them, rated as [DangerTable.Ground.landform]. */
        private val RATED_WITH_THEIR_OWN = setOf(Terrain.NETHER, Terrain.END)

        /** How many times as dangerous the book made [member]: its strength, speed, size, charge, stillness and herding. */
        private fun hardinessOf(spawns: Options, hardiness: DangerTable.Hardiness, seed: Long, member: String): Double {
            val strength = hardiness.strength.at(spawns.steer(Spawns.STRENGTH, seed, member = member))
            val pace = hardiness.pace.at(spawns.steer(Spawns.PACE, seed, member = member))
            val size = hardiness.size.at(spawns.steer(Spawns.SIZE, seed, member = member))
            val isCharged = member in CHARGEABLE && spawns.isTrue(Spawns.CHARGED, member = member)
            val charged = if (isCharged) hardiness.charged else AS_THEY_ARE
            val still = if (spawns.isTrue(Spawns.STILL, member = member)) hardiness.still else AS_THEY_ARE
            val herded = if (spawns.holdersOfAny(Spawns.HERD).includes(member)) hardiness.herds else AS_THEY_ARE
            return strength * pace * size * charged * still * herded
        }

        /**
         * Every creature the recipe names as made harder or easier, and the ordinary monster standing for the biomes'
         * own where it said all of them.
         */
        private fun madeHardier(spawns: Options): Set<String> {
            val said = spawns.holdersOfAny(Spawns.STRENGTH) and spawns.holdersOfAny(Spawns.PACE) and
                spawns.holdersOfAny(Spawns.SIZE) and spawns.holdersOfAny(Spawns.CHARGED) and
                spawns.holdersOfAny(Spawns.STILL) and spawns.holdersOfAny(Spawns.HERD)
            return if (said.everyone) said.only + ORDINARY_MONSTER else said.only
        }

        /**
         * Each phenomenon the Age runs, written or inflicted: as written, by how often and how hard; as inflicted, by how
         * far the budget bought it. Where both, the worse — it is one storm either way.
         */
        private fun phenomenaOf(
            composition: AgeComposition,
            table: DangerTable,
            spent: Spending,
            seed: Long,
        ): List<Hazard> {
            val options = composition.optionsFor(Aspect.PHENOMENA, 0)
            val dials = table.phenomenonDials
            fun asWritten(claim: Claim): Double {
                val phenomenon = Phenomenon.named(claim.value)
                val frequency = phenomenon?.let { options.steer(Phenomena.FREQUENCY, seed, member = it.key) }
                val isConstant = phenomenon?.let { Phenomena.recurrenceOf(options, it, seed).isConstant } == true
                val often = if (isConstant) dials.constant else dials.frequency.at(frequency)
                val hard = dials.strength.at(phenomenon?.let { Phenomena.strengthOf(options, it, seed) })
                return table.phenomenon(claim.value) * often * hard * confinement(claim, table)
            }
            val written = Happenings.claimsIn(composition).groupBy(Claim::value).mapValues { (_, claims) ->
                claims.maxOf(::asWritten)
            }
            val inflicted = Phenomenon.entries.associate { phenomenon ->
                val bought = phenomenon.inflictedBy?.let(spent::reach) ?: NOTHING
                phenomenon.key to table.phenomenon(phenomenon.key) * bought
            }
            return (written.keys + inflicted.keys).map { key ->
                Hazard(Source.PHENOMENON, key, maxOf(written[key] ?: NOTHING, inflicted[key] ?: NOTHING))
            }
        }

        /**
         * The worst hazard the Age asked to have placed in it — one hazard, since a volcanic Age rated with every one of
         * its four features came out at its volcano. Only what was named counts: a described feature is only more of
         * itself where it already grows, and ours grow nowhere of their own accord. Struck claims are dropped; scenery
         * is free.
         */
        private fun featuresOf(composition: AgeComposition, table: DangerTable): List<Hazard> {
            val claims = composition.optionsFor(Aspect.FEATURES, 0).claimsOn(Features.PLACES)
            fun wasNamed(claim: Claim) = !claim.onlyWhereItGrows || claim.introducedIfAbsent
            val worst = wanted(claims).filter(::wasNamed).maxByOrNull { table.feature(it.value) * confinement(it, table) }
                ?: return emptyList()
            return listOf(Hazard(Source.FEATURE, worst.value, table.feature(worst.value) * confinement(worst, table)))
        }

        /** How far past ordinary the haze closes in and the thunder rolls — only past vanilla's, since clearer is never worse. */
        private fun conditionsOf(composition: AgeComposition, table: DangerTable, seed: Long): List<Hazard> {
            fun beyondOrdinary(aspect: Aspect, parameter: Parameter) =
                composition.optionsFor(aspect, 0).steer(parameter, seed)?.coerceAtLeast(NOTHING) ?: NOTHING
            return listOf(
                Hazard(Source.CONDITION, HAZE, beyondOrdinary(Aspect.AIR, Atmosphere.HAZE) * table.condition(HAZE)),
                Hazard(Source.CONDITION, THUNDER, beyondOrdinary(Aspect.WEATHER, Atmosphere.THUNDER) * table.condition(THUNDER)),
            )
        }

        /** How heavy the Age pulls, written or inflicted, whichever is heavier. */
        private fun gravityOf(composition: AgeComposition, table: DangerTable, spent: Spending, seed: Long): Double {
            val written = composition.optionsFor(Aspect.GRAVITY, 0).steer(Gravity.PULL, seed)?.let(Gravity::factorAt) ?: AS_THEY_ARE
            val inflicted = StrangeGravity.factorFor(spent, seed)
            return table.gravity.at(maxOf(written, inflicted))
        }

        private const val HAZE = "haze"
        private const val THUNDER = "thunder"

        /** Whether the book said nothing lives here at all — `lives=nothing`. */
        private fun spawnsNothing(composition: AgeComposition): Boolean =
            Pool.NOTHING in composition.optionsFor(Aspect.SPAWNS, 0).allOf(Spawns.LIVES)

        /**
         * The claims that ask for something, removals applied last exactly as [Skew] applies them. A claim below
         * [Rung.ORDINARY] asks for less than the world grows, and the world's own share is never scored.
         */
        private fun wanted(claims: List<Claim>): List<Claim> {
            val struck = claims.filter { it.polarity == Polarity.EXCEPT }.map { it.value }.toSet()
            fun asksForLessThanTheWorld(claim: Claim) = claim.density < Rung.ORDINARY
            return claims
                .filter { it.polarity != Polarity.EXCEPT }
                .filterNot(::asksForLessThanTheWorld)
                .distinctBy { it.value }
                .filterNot { it.value in struck }
        }

        /** What a claim is worth for the ground it covers: all of it, or one biome's worth of it. */
        private fun confinement(claim: Claim, table: DangerTable): Double =
            if (claim.confinedTo == null) AS_THEY_ARE else table.confined

        /**
         * [rate] over each of [aspect]'s territories, weighted by the ground each covers. Shares are relative to the
         * widest ([Share]), so they are normalised here into fractions of the world.
         */
        private fun shareWeighted(composition: AgeComposition, aspect: Aspect, rate: (Int) -> Double): Double {
            val shares = composition.spreadOf(aspect).shares
            val whole = shares.sum()
            if (whole <= NOTHING) return NOTHING
            return shares.withIndex().sumOf { (member, share) -> rate(member) * share } / whole
        }
    }
}

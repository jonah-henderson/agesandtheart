package co.voik.agesandtheart.age.aspect

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.util.random.Weighted
import net.minecraft.util.random.WeightedList
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.util.valueproviders.UniformInt
import net.minecraft.world.level.biome.MobSpawnSettings
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * What lives here (design §3.1, vanilla's `MobSpawnSettings`) — **whatever the biomes would hold**, until a
 * sentence says otherwise.
 *
 * No preset, like the other three populations: an Age starts from what its biomes spawn and a sentence
 * adjusts it. [LIVES] is where the writing happens — naming a creature asks for it, a rung says how often,
 * `except` strikes one out, `only` keeps just what was named.
 *
 * **Natural spawning only, which is vanilla's own line.** `EntitySpawnReason` separates `NATURAL`,
 * `CHUNK_GENERATION` and `SPAWNER` from `BREEDING`, `SPAWN_EGG`, `BUCKET` and `COMMAND`; this reads the
 * first group's source — the weighted list a biome offers — and never the second. So an Age that grows no
 * life is still an Age a writer can carry a cow into, which is the rule §7.2's economy needs.
 *
 * **The seam is a plain override.** `ChunkGenerator.getMobsAt` is public, takes the biome and the position,
 * and already resolves a structure's own spawn overrides before the biome's — so filtering *its* answer
 * costs no widening and covers a fortress as well as a field.
 */
object Spawns {

    /**
     * What lives here — populative, with `only`/`except` to narrow and a rung to say how often (§3.2,
     * [Claim]). Its values are entity types. Named `lives` to avoid `spawns.spawns`.
     *
     * A mention is worth the **ordinary** amount, like a structure set and a feature: naming a creature
     * asks for one that was not there rather than for more of one that was. And it may be emptied, a
     * lifeless world being a world a writer might well want.
     */
    /**
     * Whether every creature is rooted where it stands, still watching and striking — `motionless spawns`,
     * `motionless monsters`.
     * Named for what a still body is too, so the word that stops one stops the other.
     */
    val STILL = Parameter.flag("still", help = "Whether every creature is rooted where it stands.").perMember()

    /**
     * Whether any creature the Age offers may turn up anywhere, past its own placement rules — fish on hillsides,
     * monsters at noon — `incongruous spawns`. Scarabs keep their own rules.
     */
    val INCONGRUOUS = Parameter.flag("incongruous", help = "Whether any creature may turn up anywhere, past its own rules.")

    /** How fast every creature moves, member by member — `slow monsters`, `swift spawns`. */
    val PACE = Parameter.ranged(
        "pace",
        help = "How fast creatures move.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "at a crawl"),
            Parameter.Landmark(0.0, "their own pace", isVanilla = true),
            Parameter.Landmark(1.0, "half again as fast"),
        ),
    ).perMember()

    /** How hard every creature is to kill and how hard it hits, member by member — `strong monsters`. */
    val STRENGTH = Parameter.ranged(
        "strength",
        help = "How much health creatures have, and how hard they hit.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "half their own"),
            Parameter.Landmark(0.0, "their own", isVanilla = true),
            Parameter.Landmark(1.0, "twice their own"),
        ),
    ).perMember()

    /**
     * How big every creature is, member by member — `colossal monsters`, `minuscule fish` — and its health, how
     * hard it hits and how much it drops with it. `size`, the name a sun, a feature and a landform share.
     */
    val SIZE = Parameter.ranged(
        "size",
        help = "How big creatures are, and their health, their blows and their drops with it.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "two fifths of their own"),
            Parameter.Landmark(0.0, "their own", isVanilla = true),
            Parameter.Landmark(1.0, "two and a half times their own"),
        ),
    ).perMember().onlyWhenAimed()

    val LIVES = Pool(
        "lives",
        // `lives=nothing` is no natural spawning at all, whatever the Age's biomes would hold.
        emptiedBy = Pool.NOTHING,
        help = "Which creatures live here.",
        confinable = true,
    )

    /**
     * Whether the whole Age says nothing lives in it, `lives=nothing` unconfined: an Age where no new living
     * thing may be made by anything at all (`generation/Lifeless`). Nothing confined to a biome is only
     * that biome's spawn list.
     */
    fun nothingLives(options: Options): Boolean = options.claimsOn(LIVES).any { claim ->
        val isNothing = claim.value == Pool.NOTHING && claim.polarity == Polarity.ASSERTED
        isNothing && claim.confinedTo == null
    }

    /**
     * This Age's answer for one biome's weighted list, or the list itself where nothing was said.
     *
     * Takes the list vanilla resolved rather than the biome's own, so a structure that overrides spawning
     * inside itself is narrowed by the same sentence as the open ground around it.
     */
    fun livingIn(
        options: Options,
        spawning: Spawning = Spawning(),
        /** Every creature the pack already offers somewhere — [WhereThingsGrow.creaturesListed]. */
        livesSomewhere: Set<Identifier> = emptySet(),
    ): Living {
        val claims = options.claimsOn(LIVES)
        if (LIVES.skewOf(claims).isSilent && claims.none { it.confinedTo != null }) {
            return Living { _, _, _, offered -> offered }
        }
        // **Asked per biome, because a claim may be confined to one** (§4.3.1). Remembered for the same
        // reason the feature settings are: the answer is the same every time and the question is asked
        // once per spawn attempt.
        val here = ConcurrentHashMap<Identifier, Skew>()
        // And so is **which creatures could arrive at all**, on the same argument: the registry lookup,
        // the pass filter and the entry itself are the same answer every time, and only a *position*
        // decides whether one of them may be tried here.
        val couldArrive = ConcurrentHashMap<Arrivals, List<Arriving>>()
        val everywhere = LIVES.skewOf(claims)
        fun askedIn(biome: Identifier?): Skew =
            biome?.let { here.computeIfAbsent(it) { where -> LIVES.skewOf(claims, where) } } ?: everywhere
        return object : Living {
            override fun at(
                biome: Identifier?,
                category: MobCategory,
                where: Situation,
                offered: WeightedList<MobSpawnSettings.SpawnerData>,
            ): WeightedList<MobSpawnSettings.SpawnerData> {
                val asked = askedIn(biome)
                val kept = narrowed(offered, asked)
                val candidates = couldArrive.computeIfAbsent(Arrivals(biome, category)) {
                    resolved(asked, category, spawning, livesSomewhere)
                }
                return added(kept, candidates, where)
            }

            override fun rungOf(creature: Identifier, biome: Identifier?): Double {
                val asked = askedIn(biome)
                val isStruck = creature.toString() in asked.struck
                val named = asked.wanted.firstOrNull { it.id == creature }?.density
                return when {
                    isStruck -> 0.0
                    named != null -> named
                    asked.startsFromNothing -> 0.0
                    else -> Rung.ORDINARY
                }
            }
        }
    }

    /**
     * This Age's answer for one biome's list at one place: which biome, which spawn pass, and whether the
     * sky is open where the attempt is being made.
     *
     * A named interface rather than a function type because it grew a third argument and a `(Identifier?,
     * MobCategory, Boolean, WeightedList) -> WeightedList` at a call site says nothing about any of them.
     */
    fun interface Living {
        fun at(
            biome: Identifier?,
            category: MobCategory,
            where: Situation,
            offered: WeightedList<MobSpawnSettings.SpawnerData>,
        ): WeightedList<MobSpawnSettings.SpawnerData>

        /**
         * How thickly the sentence asks for [creature] in [biome]: the rung it was named or leaned at, nothing where
         * it was struck or left out of an `only`, and the ordinary rung where nothing was said of it. For a creature
         * that is rare by its own spawn rule, whose weight alone in a list would move nothing.
         */
        fun rungOf(creature: Identifier, biome: Identifier?): Double = Rung.ORDINARY
    }

    /**
     * **Everything about a place that decides whether a creature belongs in it** — where it is, whether the
     * sky is open over it, whether it is day out, and how lit it is.
     *
     * One argument rather than four, because three of them arrived one at a time as the judgements did and
     * a call site reading `(null, MONSTER, true, false, 7, pos, list)` says nothing about any of them.
     */
    data class Situation(
        val at: BlockPos,
        /** Whether this is out under the sky, which is the ground rule's whole question — see [Ground]. */
        val skyIsOpen: Boolean,
        /** How lit it is here, on vanilla's own `0..15` — see [Lit]. */
        val brightness: Int,
    )

    /**
     * One weighted list with the sentence applied: struck creatures dropped, named ones weighted by the
     * rung they were asked at, and everything unnamed dropped where the writer said `only` or [Pool.NOTHING].
     *
     * Adding what the list *lacks* is [added]'s, and the two are deliberately apart: this one can only ever
     * take away or reweight.
     */
    private fun narrowed(
        offered: WeightedList<MobSpawnSettings.SpawnerData>,
        asked: Skew,
    ): WeightedList<MobSpawnSettings.SpawnerData> {
        if (asked.isSilent) return offered
        val struck = asked.struck.mapNotNull(Identifier::tryParse).toSet()
        val weights = asked.wanted
            .mapNotNull { claim -> claim.id?.let { it to claim.density } }
            .toMap()
        val emptied = asked.startsFromNothing
        val kept = offered.unwrap().mapNotNull { entry ->
            val id = idOf(entry.value().type())
            val asked = weights[id]
            val leftOutOfAnOnly = emptied && asked == null
            if (id in struck || leftOutOfAnOnly) return@mapNotNull null
            if (asked == null || Rung.isOrdinary(asked)) entry else Weighted(entry.value(), howOften(entry, asked))
        }
        return WeightedList.of(kept)
    }

    /**
     * **A creature the sentence asked for and the world never offered, added** — the same operation as
     * skewing one that was there, from zero.
     *
     * The design always said so: naming a member of a weighted set skews it (world model §6), and a member
     * the template weights at nothing is still a member. Only the implementation could not say it, because
     * it walked what a biome offered and nothing else.
     *
     * **This is safe because a biome's list is a menu and not a promise.** Every attempt vanilla makes goes
     * through `SpawnPlacements.isSpawnPositionOk` and `checkSpawnRules`, so a cod named into a desert is
     * refused at the position and never appears — the older reading, that adding one would put it somewhere
     * it cannot live, had the gate in the wrong place.
     *
     * Two rules of our own, for the two things vanilla cannot answer:
     *
     * - **A creature arrives in the pass its own category names**, so a monster is tried under the monster
     *   rules and against the monster cap.
     * - **A creature vanilla never spawns has no placement rules at all**, so `NO_RESTRICTIONS` would try a
     *   dragon inside a mountain. Its [Ground], read by [Spawning.groundOf], says where it may be tried.
     */
    private fun added(
        kept: WeightedList<MobSpawnSettings.SpawnerData>,
        candidates: List<Arriving>,
        where: Situation,
    ): WeightedList<MobSpawnSettings.SpawnerData> {
        if (candidates.isEmpty()) return kept
        // **Only what a position decides is asked here**; everything else was settled once — see [resolved].
        // Unwrapped once rather than per candidate: this runs on every spawn attempt in the Age.
        val already = kept.unwrap()
        fun mayArriveHere(arriving: Arriving): Boolean {
            val theGroundIsWrong = !arriving.ground.admits(where.skyIsOpen)
            val theLightIsWrong = !arriving.light.admits(where.brightness)
            val itIsHeldApartFromHere = !arriving.arrival.mayBeTriedAt(where.at.x, where.at.z, arriving.spacing)
            val isOfferedAlready = already.any { it.value().type() === arriving.type }
            return !theGroundIsWrong && !theLightIsWrong && !itIsHeldApartFromHere && !isOfferedAlready
        }
        val arriving = candidates.filter(::mayArriveHere)
        if (arriving.isEmpty()) return kept
        return WeightedList.of(already + arriving.map { it.offered })
    }

    /** Which biome's list, and which of vanilla's passes — what a set of arrivals is the answer to. */
    private data class Arrivals(val biome: Identifier?, val category: MobCategory)

    /** One creature the sentence asked for, taken as far as a question with no position in it can go. */
    private class Arriving(
        /** Compared by reference against what the biome already offers — a registry lookup per attempt else. */
        val type: EntityType<*>,
        val arrival: Arrival,
        /** Read once here rather than per attempt: a tag lookup is cheap and this is asked constantly. */
        val ground: Ground,
        /** And what light — see [Lit]. */
        val light: Lit,
        /** How far apart this creature stands, with whatever rung was asked for already in it. */
        val spacing: Int,
        val offered: Weighted<MobSpawnSettings.SpawnerData>,
    )

    /**
     * Every creature a claim **names** and how thickly, as ids — what the Age places itself, which is a
     * shorter list than what it offers a biome: [resolved] stocks a menu and this summons.
     */
    fun claimedCreatures(options: Options): List<Pair<Identifier, Double>> =
        options.skewOn(LIVES).wanted
            // **Summoning one takes naming it.** This is the Age's own placement — the golems, the wither,
            // the dragon — and a boss is not an atmosphere: a word brushing the dragon through `hostile`
            // put one in the sky of a beautiful Age. `teeming ender_dragon` still names it outright, and
            // nothing placed by the Age is offered by any biome, so a naming always brings it.
            .filter { it.introduces(growsSomewhere = false) }
            .mapNotNull { claim -> claim.id?.let { it to claim.density } }

    /**
     * Every creature [asked] wants that vanilla can be told to try in [category].
     *
     * **Settled once per biome and pass**, because none of it moves: the registry lookup, the pass filter,
     * the weight and the entry itself are the same answer at every position, and this is asked once per
     * spawn attempt. Measured at 0.9µs an attempt before, against 0.005µs for an Age that said nothing.
     */
    private fun resolved(
        asked: Skew,
        category: MobCategory,
        spawning: Spawning,
        livesSomewhere: Set<Identifier>,
    ): List<Arriving> {
        // A creature named without `everywhere` comes in only where the pack offers it nowhere; one that
        // lives somewhere is thickened there by [narrowed] instead. **A description never stocks a menu**
        // (Jonah, 2026-10-16): `teeming monsters` is more monsters where monsters live, and it was putting
        // zombie nautiluses and hadalfish on offer in every biome. `everywhere` is how a writer asks for
        // them in every place, and its claims are not descriptions.
        fun mayBeAdded(claim: Claim): Boolean = when {
            claim.introducedIfAbsent -> claim.introduces(claim.id in livesSomewhere)
            claim.onlyWhereItGrows -> false
            else -> !claim.bringsNothingAbout
        }
        return asked.wanted
            // **What keeps an added creature where it can live is [Spawning.placeWhereTheyBelong]**: a
            // creature of ours with no registered placement is put on dry ground by vanilla's own default,
            // which is how a hadalfish hunted a hillside (Jonah, 2026-09-17, the Age Tumar).
            .filter(::mayBeAdded)
            .mapNotNull { claim -> claim.id?.let { it to claim.density } }
            .mapNotNull { (id, density) ->
                // **Asked whether it is there before asking what it is.** The entity registry is a
                // *defaulted* one, so an id it has never heard of comes back as `minecraft:pig` rather
                // than as nothing — and the parameter's own `unchanged` placeholder is such an id, which
                // is how writing a golem quietly put a pig in the world.
                if (!BuiltInRegistries.ENTITY_TYPE.containsKey(id)) return@mapNotNull null
                // **Asking for one here is asking for a draw it can only lose**: vanilla refuses these
                // after the list, so offering one costs the biome's own creatures a share of every attempt
                // and puts nothing in the world. `AgeSpawner` has them.
                if (spawning.isPlacedByTheAge(id)) return@mapNotNull null
                val type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null)
                    ?: return@mapNotNull null
                val arrival = spawning.of(id)
                if (spawnPassFor(type) != category) return@mapNotNull null
                val entry = MobSpawnSettings.SpawnerData(type, UniformInt.of(arrival.least, arrival.most))
                val ground = spawning.groundOf(id)
                val light = spawning.lightOf(id)
                val spacing = arrival.spacedAt(density)
                // **A rung spends itself once.** On a creature held apart it moved the spacing above, so
                // the weight takes only what the spacing costs; on one held apart from nothing there is
                // no spacing to move and the rung is the weight, as it always was.
                val thickened = if (spacing > 0) 1.0 else density
                val asked = arrival.weight * thickened * arrival.thinningAt(spacing) * keptTo(ground)
                Arriving(
                    type, arrival, ground, light, spacing,
                    Weighted(entry, asked.roundToInt().coerceIn(1, MOST_OFTEN)),
                )
            }
    }

    /**
     * What belonging to one ground costs, which is **not** the share of attempts that land there.
     *
     * An attempt underground overwhelmingly fails for *everyone* — it is solid rock — so refusing a
     * surface creature there costs it almost nothing it would have won. What it really costs is the cave
     * spawns, which are a large slice of what a monster actually gets, and no arithmetic available here
     * measures that. A named number rather than a derived one, therefore, and the one thing in this file
     * that genuinely wants a walk: paying back the *attempt* share instead would be a factor of sixty-odd
     * and would leave the surface knee-deep in whatever was written.
     */
    private fun keptTo(ground: Ground): Double = if (ground == Ground.ANYWHERE) 1.0 else KEPT_TO_ONE_GROUND

    /** What belonging to one ground hands back. A first guess, and it is meant to be walked. */
    private const val KEPT_TO_ONE_GROUND = 4.0

    /**
     * Which spawn pass a creature arrives in. Its own category, because vanilla runs a pass per category
     * and a monster offered to the creature pass would be tried under the creature's rules, in daylight
     * and against the wrong cap.
     *
     * **The built ones arrive as creatures.** `MISC` is not a pass — `NaturalSpawner` runs every category
     * but that one — so a golem offered under its own would never be tried at all. What it behaves like is
     * a creature, and the creature pass is the only one that would ever have it.
     */
    private fun spawnPassFor(type: EntityType<*>): MobCategory =
        if (type.category == MobCategory.MISC) MobCategory.MONSTER else type.category

    /** A weight scaled by the rung, never to nothing: an entry at zero would never be drawn at all. */
    private fun howOften(entry: Weighted<MobSpawnSettings.SpawnerData>, rung: Double): Int =
        (entry.weight() * rung).roundToInt().coerceIn(1, MOST_OFTEN)

    private const val MOST_OFTEN = 1000

    private fun idOf(type: EntityType<*>): Identifier = BuiltInRegistries.ENTITY_TYPE.getKey(type)
}

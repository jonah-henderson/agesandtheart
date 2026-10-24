package co.voik.agesandtheart.age

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.datapack.PerReload
import co.voik.agesandtheart.datapack.ResourceParsing
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.server.MinecraftServer
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.util.StringRepresentable
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

/*
 * A deluge's dials. Top-level rather than in the companion: an enum constant is built before its own
 * companion exists, so a constant beside them is the only one they can read.
 */
private const val DELUGE_RATE = "rate"
private const val DELUGE_DOWNPOUR = "downpour"
private const val DELUGE_HEIGHT = "height"

// A meteor storm's dials and a collapse's, for the same reason.
private const val METEORS_OFTEN = "often"
private const val METEORS_LONG = "long"
private const val METEORS_POWER = "power"
private const val TECTONICS_SIZE = "size"
private const val TECTONICS_SPEED = "speed"
private const val SANDFALL_OFTEN = "often"
private const val SANDFALL_SIZE = "size"
private const val SANDFALL_LONG = "long"
private const val SANDFALL_DEPTH = "depth"
private const val BLIZZARD_OFTEN = "often"
private const val BLIZZARD_LONG = "long"
private const val BLIZZARD_VISIBILITY = "visibility"
private const val BLIZZARD_FROSTBITE = "frostbite"
private const val TEMPEST_OFTEN = "often"
private const val TEMPEST_BLAST = "blast"
private const val TEMPEST_FIRE = "fire"
private const val INFERNO_BURN_DAMAGE = "burn_damage"
private const val INFERNO_LIGHT_INTENSITY = "light_intensity"
private const val FOG_OFTEN = "often"
private const val FOG_THICKNESS = "thickness"
private const val SKY_DRIFT_STRENGTH = "strength"
private const val SKY_DRIFT_PACE = "pace"
private const val DAY_STARS_BRIGHTNESS = "brightness"
private const val LOW_CLOUDS_LOWNESS = "lowness"
private const val STILL_CREATURES_ROOTED = "rooted"
private const val STAMPED_SHAPES_STAMPED = "stamped"
private const val LEAFLESS_TREES_BARE = "bare"
private const val ABANDONED_VILLAGES_ABANDONED = "abandoned"
private const val UNCANNY_MOTES_DENSITY = "density"
private const val UNCANNY_MOTES_SIZE = "size"
private const val MONOCHROME_DRAINED = "drained"
private const val SLOW_RAIN_SLOWNESS = "slowness"
private const val SKY_TEARS_MANY = "many"
private const val STRANGE_GRAVITY_STRENGTH = "strength"
private const val FOREIGN_GROUND_EXTENT = "extent"
private const val OVERGROWN_GROUND_PATCHES = "patches"

/**
 * A way an Age shows what is wrong with it — **the things instability is spent on** (design §5.0).
 *
 * Each has one or more **dials**, and each dial sells steps, each step in a tier. Every distinct flaw buys one
 * step, drawn off the Age's seed and climate, so two Ages with the same flaws come apart differently; see
 * [Spending].
 */
enum class Manifestation(val key: String, vararg dials: String) : StringRepresentable {
    /**
     * The seams between territories tear rather than meet: scarps throw further, rifts cut deeper, walls
     * stand higher, and a dissolve widens from a transition into two worlds failing to decide.
     *
     * **Only in an Age that has territories to tear** (Jonah, 2026-10-12): an undivided Age has no seam, so
     * a step here would show nothing. `Seam.SHEARED` is not widened at all: terrain on either side of a shear
     * is usually dramatic enough to read as instability already (Jonah, 2026-08-07).
     */
    TORN_SEAMS("torn_seams", "width"),

    /**
     * Tears in spacetime open in the ground (design §5.1) — small, unlit, and impossible to do anything
     * with but wall in.
     *
     * What a step buys is **how often one opens**, as a chance per chunk: a count would be a handful nobody
     * ever walks past, where a frequency is a thing you meet while doing something else.
     */
    WOUNDS("wounds", "frequency"),

    /**
     * The tearing does not stop (design §5.2.1) — the Age goes on holing itself for as long as it exists.
     *
     * **A verdict rather than a fight.** What a step buys is how *fast* the wound density climbs with the
     * Age's age, so [WOUNDS] is how holed it was written and this is how holed it becomes. Unbounded on
     * purpose: a ceiling would promise the Age can be outlasted.
     */
    WORSENING_WOUNDS("worsening_wounds", "pace"),

    /**
     * Columns of sand walk the Age (design §5.2.2) — bigger, hungrier, longer-lived and more of them at
     * once the further its steps reach.
     *
     * **Puts a *phenomenon* into an Age nobody wrote one into, and that is the point rather than a leak**
     * (Jonah, 2026-08-31): a column walking a world that never asked for one is exactly what the index is
     * for (§7.7). Where the Age already has a sandfall, the two **compound**.
     *
     * **Four dials** (Jonah, 2026-09-23): how often a column comes and how many may stand at once, how long
     * one lives, how wide it grows, and how deep it buries. The last two are what takes a base.
     */
    SANDFALL("sandfall", SANDFALL_OFTEN, SANDFALL_LONG, SANDFALL_SIZE, SANDFALL_DEPTH),

    /**
     * The Age is driven under snow (design §5.2's blizzard). **Four dials** (Jonah, 2026-09-23): how often a
     * storm comes, how long each lasts, how far you can see in one (and how hard the snow drifts), and how
     * fast the cold gets into you.
     */
    BLIZZARD("blizzard", BLIZZARD_OFTEN, BLIZZARD_LONG, BLIZZARD_VISIBILITY, BLIZZARD_FROSTBITE),

    /**
     * The sky falls on the Age in showers (design §5.2). **Three dials** (Jonah, 2026-09-23): how often a storm
     * gathers, how long it lasts, and how hard its bodies land. How many bodies a storm drops follows how long it
     * lasts and nothing else.
     */
    METEORS("meteors", METEORS_OFTEN, METEORS_LONG, METEORS_POWER),

    /**
     * The ground gives way under itself (design §5.2, §5.3's collapse asked of one hillside at a time).
     *
     * **Two dials, size and speed, and never the warning.** The warning is the mechanism rather than a
     * difficulty setting: a collapse that arrived unannounced would not be harder, it would be a different
     * and worse thing.
     */
    TECTONICS("tectonics", TECTONICS_SIZE, TECTONICS_SPEED),

    /**
     * The Age is struck (design §5.2) — a storm that does not end, bolts coming down far more often than
     * weather alone would bring them, and cratering the ground where they land. **Three dials** (Jonah,
     * 2026-09-23): how often bolts come, how big a crater one digs, and how much it sets alight. A lightning rod
     * still grounds a bolt, whatever was bought.
     */
    TEMPEST("tempest", TEMPEST_OFTEN, TEMPEST_BLAST, TEMPEST_FIRE),

    /**
     * The Age is set alight (design §5.2) — the sun scours frost off the ground and lights whatever will
     * burn, in a world that was never written to be fiery.
     *
     * **Two dials** (Jonah, 2026-09-23), which replace the single step it had: how much it hurts to stand
     * out under open light, and how dim that light may get before the burning stops — bought in full, the
     * moon burns too. Either one bought sets the Age alight.
     */
    INFERNO("inferno", INFERNO_BURN_DAMAGE, INFERNO_LIGHT_INTENSITY),

    /**
     * The sea comes up under a downpour that will not stop (design §5.2's deluge).
     *
     * **Three dials** (Jonah, 2026-09-17): how fast the sea climbs while it rains, how much of the time it rains,
     * and how far the sea climbs — whose last steps are failing, so only a failing Age drowns to the build
     * limit.
     */
    DELUGE("deluge", DELUGE_RATE, DELUGE_DOWNPOUR, DELUGE_HEIGHT),

    /**
     * The world comes apart at the bottom (design §5.3) — fissures open at the world floor and widen, and
     * what falls in is put back in the overworld. A verdict, at the top tier; each further step buys how fast
     * the tear spreads.
     */
    COLLAPSE("collapse", "spread"),

    /**
     * Banks of heavy fog roll over the Age and lift again (Jonah, 2026-10-12) — strange, the first phenomenon
     * that is only ever seen. **Two dials**: how much of the time a bank lies over the Age, and how close it
     * closes in.
     */
    FOG("fog", FOG_OFTEN, FOG_THICKNESS),

    /**
     * The sky wanders slowly through colours no sky should be (Jonah, 2026-10-12). Strange: seen only.
     * **Two dials**: how far it is drawn from its own colour, and how fast it wanders.
     */
    SKY_DRIFT("sky_drift", SKY_DRIFT_STRENGTH, SKY_DRIFT_PACE),

    /** The stars stay out through the day — faint at first, then as bright as at midnight. Strange. */
    DAY_STARS("day_stars", DAY_STARS_BRIGHTNESS),

    /** The cloud layer comes down to where people walk. Strange. */
    LOW_CLOUDS("low_clouds", LOW_CLOUDS_LOWNESS),

    /** Every creature in the Age is rooted where it stands, though it still watches and strikes. Strange. */
    STILL_CREATURES("still_creatures", STILL_CREATURES_ROOTED),

    /** Every tree, boulder and patch in the Age comes out the same shape, as if stamped. Strange. */
    STAMPED_SHAPES("stamped_shapes", STAMPED_SHAPES_STAMPED),

    /** The Age's trees grow bare: trunk and branches and no leaves at all. Strange. */
    LEAFLESS_TREES("leafless_trees", LEAFLESS_TREES_BARE),

    /** Every village in the Age is vanilla's abandoned one, cobwebs and zombie villagers. Strange. */
    ABANDONED_VILLAGES("abandoned_villages", ABANDONED_VILLAGES_ABANDONED),

    /**
     * Motes hang in the Age's air that no air should hold: they rise, or they hang perfectly still, in a look
     * the Age draws for itself. **Two dials**: how many, and how big. Strange.
     */
    UNCANNY_MOTES("uncanny_motes", UNCANNY_MOTES_DENSITY, UNCANNY_MOTES_SIZE),

    /** The whole Age is seen in grey, all the time. Strange. */
    MONOCHROME("monochrome", MONOCHROME_DRAINED),

    /** The rain falls slowly, and patters lower. **One dial**: how slowly. Strange. */
    SLOW_RAIN("slow_rain", SLOW_RAIN_SLOWNESS),

    /** Thin rifts of light hang torn high in the sky, still. **One dial**: how many. Strange. */
    SKY_TEARS("sky_tears", SKY_TEARS_MANY),

    /**
     * Everything in the Age falls lighter or heavier than it should, the seed deciding which (Jonah, 2026-10-13).
     * **One dial** climbing the tiers: a little at its first step, which is strange, and a great deal at its
     * second, which is harmful.
     */
    STRANGE_GRAVITY("strange_gravity", STRANGE_GRAVITY_STRENGTH),

    /**
     * Patches of the ground are another country's: the Age's own terrain read from far away, so the land
     * breaks off at a patch's edge and carries on as something that was never meant to be there. **One dial**:
     * how much of the Age is foreign. Strange, and only in ground generated afterwards.
     */
    FOREIGN_GROUND("foreign_ground", FOREIGN_GROUND_EXTENT),

    /**
     * Large patches of overgrowth lie over the new ground ([co.voik.agesandtheart.age.phenomena.OvergrowthPatches]),
     * about twenty-five blocks across, which then spread as roots do. **One dial**: how many chunks hold one.
     *
     * **Not the phenomenon**: nothing seeds blocks at random here, so a book that wrote no overgrowth gets the
     * patches and nothing else. Harmful, and only in ground generated afterwards.
     */
    OVERGROWN_GROUND("overgrown_ground", OVERGROWN_GROUND_PATCHES),
    ;

    /** What this manifestation's ladder sells steps of, in the order its file is read. */
    val dials: List<String> = dials.toList()

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<Manifestation> = StringRepresentable.fromEnum(Manifestation::values)

        /** How fast a deluge's sea climbs while it rains. */
        const val RISE_RATE = DELUGE_RATE

        /** How much of the time a deluge's rain falls. */
        const val DOWNPOUR = DELUGE_DOWNPOUR

        /** How far over its written level a deluge's sea is carried. */
        const val RISE_HEIGHT = DELUGE_HEIGHT

        /** How often a meteor storm gathers. */
        const val STORMS_OFTEN = METEORS_OFTEN

        /** How long a meteor storm lasts, and so how many bodies it drops. */
        const val STORMS_LONG = METEORS_LONG

        /** How hard a meteor storm's bodies land. */
        const val STORMS_POWER = METEORS_POWER

        /** How much ground one cave-in takes. */
        const val CAVE_IN_SIZE = TECTONICS_SIZE

        /** How fast a cave-in goes once its warning is over. */
        const val CAVE_IN_SPEED = TECTONICS_SPEED

        /** How often a sand column comes, and how many may stand at once. */
        const val COLUMNS_OFTEN = SANDFALL_OFTEN

        /** How long a sand column lives. */
        const val COLUMNS_LONG = SANDFALL_LONG

        /** How wide a sand column grows. */
        const val COLUMNS_SIZE = SANDFALL_SIZE

        /** How deep one pass of a sand column buries. */
        const val COLUMNS_DEPTH = SANDFALL_DEPTH

        /** How often a blizzard comes. */
        const val SNOWSTORMS_OFTEN = BLIZZARD_OFTEN

        /** How long a blizzard lasts. */
        const val SNOWSTORMS_LONG = BLIZZARD_LONG

        /** How far you can see in a blizzard, and how hard its snow drifts. */
        const val SNOWSTORMS_VISIBILITY = BLIZZARD_VISIBILITY

        /** How fast a blizzard's cold gets into you. */
        const val SNOWSTORMS_FROSTBITE = BLIZZARD_FROSTBITE

        /** How often a tempest's bolts come. */
        const val BOLTS_OFTEN = TEMPEST_OFTEN

        /** How big a crater a tempest's bolt digs. */
        const val BOLTS_BLAST = TEMPEST_BLAST

        /** How much a tempest's bolt sets alight. */
        const val BOLTS_FIRE = TEMPEST_FIRE

        /** How much an inferno hurts anything out under open light. */
        const val BURN_DAMAGE = INFERNO_BURN_DAMAGE

        /** How dim the light may be before an inferno stops burning. */
        const val LIGHT_INTENSITY = INFERNO_LIGHT_INTENSITY

        /** How much of the time a bank of fog lies over the Age. */
        const val BANKS_OFTEN = FOG_OFTEN

        /** How close a bank of fog closes in. */
        const val BANKS_THICKNESS = FOG_THICKNESS

        /** How far a drifting sky is drawn from its own colour. */
        const val DRIFT_STRENGTH = SKY_DRIFT_STRENGTH

        /** How fast a drifting sky wanders between colours. */
        const val DRIFT_PACE = SKY_DRIFT_PACE

        /** How bright the stars stay by day. */
        const val STARS_BY_DAY = DAY_STARS_BRIGHTNESS

        /** How far the cloud layer comes down. */
        const val CLOUDS_LOWNESS = LOW_CLOUDS_LOWNESS

        /** How many uncanny motes hang in the air. */
        const val MOTES_DENSITY = UNCANNY_MOTES_DENSITY

        /** How big an uncanny mote is. */
        const val MOTES_SIZE = UNCANNY_MOTES_SIZE

        /** How slowly the rain falls. */
        const val RAIN_SLOWNESS = SLOW_RAIN_SLOWNESS

        /** How many tears hang in the sky. */
        const val TEARS_MANY = SKY_TEARS_MANY

        /** How far an Age's gravity is from vanilla's. */
        const val GRAVITY_STRENGTH = STRANGE_GRAVITY_STRENGTH
    }
}

/**
 * The tier of each step one dial sells, in order (design §5.0).
 *
 * Written as one tier for every step (`"tier": 1, "most": 4`) or a list with one entry per step
 * (`"tiers": [1, 1, 2, 2]`), so a dial can climb from a nuisance into a hazard.
 */
data class DialSteps(val tiers: List<Int>) {
    init {
        require(tiers.all { it in Spending.TIERS }) { "a dial's steps are tiered $tiers, outside ${Spending.TIERS}" }
    }

    /** How many steps it sells. */
    val most: Int get() = tiers.size

    companion object {
        /** [most] steps, every one at [tier]. */
        fun flat(tier: Int, most: Int) = DialSteps(List(most) { tier })

        /** The file's own shape, before a single tier is spread over every step. */
        private data class Written(val tier: Optional<Int>, val most: Optional<Int>, val tiers: Optional<List<Int>>)

        private val WRITTEN: Codec<Written> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.optionalFieldOf("tier").forGetter(Written::tier),
                Codec.INT.optionalFieldOf("most").forGetter(Written::most),
                Codec.INT.listOf().optionalFieldOf("tiers").forGetter(Written::tiers),
            ).apply(instance, ::Written)
        }

        private fun read(written: Written): DataResult<DialSteps> {
            val listed = written.tiers.orElse(null)
            val tiers = when {
                listed != null && written.tier.isPresent -> return DataResult.error { "a dial says both \"tier\" and \"tiers\"" }
                listed != null -> listed
                written.tier.isPresent && written.most.isPresent -> List(written.most.get()) { written.tier.get() }
                else -> return DataResult.error { "a dial needs \"tiers\", or a \"tier\" and how many steps it sells as \"most\"" }
            }
            if (tiers.any { it !in Spending.TIERS }) return DataResult.error { "tiers $tiers fall outside ${Spending.TIERS}" }
            return DataResult.success(DialSteps(tiers))
        }

        private fun write(steps: DialSteps) = Written(Optional.empty(), Optional.empty(), Optional.of(steps.tiers))

        val CODEC: Codec<DialSteps> = WRITTEN.comapFlatMap(::read, ::write)
    }
}

/**
 * How one manifestation climbs the tiers, and where it belongs — **datapack content**
 * (`art/manifestation/<key>.json`).
 *
 * Content rather than config (`notes/config-research.md`): a pack that makes the verdicts easy to reach is a
 * different mod rather than the same one run differently, and the whole ladder being one directory is the
 * tuning surface.
 */
data class Ladder(
    /** What each of the manifestation's dials sells, by the dial's name. */
    val dials: Map<String, DialSteps>,
    /**
     * Which way it leans on the finished Age's climate, by axis — `temperature`, `humidity` — from −1 to 1:
     * a blizzard leans cold, an inferno hot. An axis it says nothing about does not move it.
     */
    val leans: Map<String, Double> = emptyMap(),
    /** What it can never share an Age with. Read both ways, so a pair need only be written on one side. */
    val excludes: Set<Manifestation> = emptySet(),
    /**
     * What an Age must have for it to show at all — torn seams need territories to tear, barren trees need
     * trees. Never bought where one is missing, since a step there would show nothing (Jonah, 2026-10-12).
     */
    val needs: Set<Showing> = emptySet(),
    /**
     * The parameters a word sets to the same effect, as `page.parameter` — `gravity.pull`. Never bought where
     * the book already set one, since the writer's word is law.
     */
    val heldBy: Set<String> = emptySet(),
    /** How often it is drawn against the rest, one being as often as any: what a world without colour is made rare by. */
    val weight: Double = ORDINARY_WEIGHT,
) {
    companion object {
        private const val ORDINARY_WEIGHT = 1.0
        private const val DEFAULT_TIER = Spending.HARMFUL
        private const val DEFAULT_MOST = 4

        /** What a manifestation nobody wrote a ladder for does — so an absent file is a default, never nothing. */
        fun ordinaryFor(manifestation: Manifestation) =
            Ladder(manifestation.dials.associateWith { DialSteps.flat(DEFAULT_TIER, DEFAULT_MOST) })

        /** [most] steps at [tier] on every dial [manifestation] has — the whole of a simple ladder. */
        fun flat(manifestation: Manifestation, tier: Int, most: Int) =
            Ladder(manifestation.dials.associateWith { DialSteps.flat(tier, most) })

        val CODEC: Codec<Ladder> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.unboundedMap(Codec.STRING, DialSteps.CODEC).fieldOf("dials").forGetter(Ladder::dials),
                Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).optionalFieldOf("leans", emptyMap()).forGetter(Ladder::leans),
                Manifestation.CODEC.listOf().optionalFieldOf("excludes", emptyList()).forGetter { it.excludes.toList() },
                Showing.CODEC.listOf().optionalFieldOf("needs", emptyList()).forGetter { it.needs.toList() },
                Codec.STRING.listOf().optionalFieldOf("held_by", emptyList()).forGetter { it.heldBy.toList() },
                Codec.DOUBLE.optionalFieldOf("weight", ORDINARY_WEIGHT).forGetter(Ladder::weight),
            ).apply(instance) { dials, leans, excludes, needs, heldBy, weight ->
                Ladder(dials, leans, excludes.toSet(), needs.toSet(), heldBy.toSet(), weight)
            }
        }

        const val DIRECTORY = "art/manifestation"

        /** The ladders this server is running, read once per datapack load. */
        fun list(server: MinecraftServer): Map<Manifestation, Ladder> = current.of(server)

        private val current = PerReload { server -> load(server.resourceManager) }

        /** The ladders in [resources] — the whole of the loading, and usable offline. */
        fun load(resources: ResourceManager): Map<Manifestation, Ladder> {
            val problems = mutableListOf<String>()
            val ladders = buildMap {
                for (manifestation in Manifestation.entries) put(manifestation, ordinaryFor(manifestation))
                for ((file, resource) in resources.listResources(DIRECTORY, ResourceParsing::isJson)) {
                    val key = ResourceParsing.nameUnder(file, DIRECTORY)
                    val named = Manifestation.entries.firstOrNull { it.key == key }
                    if (named == null) {
                        problems += "$file names no manifestation, so nothing reads it"
                        continue
                    }
                    val ladder = ResourceParsing.parse(resource, file, CODEC, problems) ?: continue
                    put(named, fittedTo(named, ladder, file.toString(), problems))
                }
            }
            for (problem in problems) Constants.LOG.warn("Manifestation ladders: {}", problem)
            return ladders
        }

        /** [ladder] with exactly [manifestation]'s dials: a misspelt one dropped, a missing one laddered ordinarily. */
        private fun fittedTo(
            manifestation: Manifestation,
            ladder: Ladder,
            file: String,
            problems: MutableList<String>,
        ): Ladder {
            for (unread in ladder.dials.keys - manifestation.dials.toSet()) {
                problems += "$file ladders a dial '$unread' that ${manifestation.key} does not have"
            }
            val dials = manifestation.dials.associateWith { dial ->
                ladder.dials[dial] ?: DialSteps.flat(DEFAULT_TIER, DEFAULT_MOST).also {
                    problems += "$file does not ladder ${manifestation.key}'s '$dial', so it takes the default"
                }
            }
            return ladder.copy(dials = dials)
        }
    }
}

/**
 * What an Age's instability actually bought (design §5.0).
 *
 * **Derived, never stored.** A pure function of the instability, the seed, the ladders and the Age's setting,
 * so it comes out the same on every open and needs no room in the recipe.
 *
 * **Each distinct flaw buys one step**, in a tier set by how many there are and capped by how long the book
 * was: a short book can only ever be strange, and a long one with many flaws reaches the verdicts. The flaws
 * are dealt out across the tiers as evenly as they go, the top one always getting at least one, and each step
 * is drawn by the Age's own leaning and its climate's, within what the manifestations already drawn allow.
 */
data class Spending(
    private val steps: Map<Manifestation, Map<String, Int>>,
    /** How many steps each dial sells, on the ladders this was bought from. */
    private val ceilings: Map<Manifestation, Map<String, Int>> = emptyMap(),
) {

    /** How many steps of [manifestation]'s [dial] were bought. */
    fun bought(manifestation: Manifestation, dial: String): Int = steps[manifestation]?.get(dial) ?: 0

    /** How many steps of [manifestation] were bought across all its dials — zero where nothing reached it. */
    fun bought(manifestation: Manifestation): Int = steps[manifestation]?.values?.sum() ?: 0

    /**
     * How far into [manifestation]'s [dial] this Age went, from nothing to everything it sells — the number
     * a phenomenon scales by, so a caller never has to know how a dial was laddered.
     */
    fun reach(manifestation: Manifestation, dial: String): Double {
        val most = ceilings[manifestation]?.get(dial) ?: return 0.0
        if (most <= 0) return 0.0
        return bought(manifestation, dial).toDouble() / most
    }

    /** How far into [manifestation] this Age went, averaged over its dials — for one dial, that dial's reach. */
    fun reach(manifestation: Manifestation): Double =
        manifestation.dials.sumOf { reach(manifestation, it) } / manifestation.dials.size

    /**
     * This with each of [overrides] set by hand: every dial of the manifestation at that many steps, or as many
     * as it sells on [ladders], and nought taking it away — whatever was drawn and whatever it excludes.
     */
    fun forced(overrides: Map<Manifestation, Int>, ladders: Map<Manifestation, Ladder>): Spending {
        if (overrides.isEmpty()) return this
        val laddered = Manifestation.entries.associateWith { manifestation ->
            (ladders[manifestation] ?: Ladder.ordinaryFor(manifestation)).dials.mapValues { it.value.most }
        }
        val forcedSteps = steps.toMutableMap()
        for ((manifestation, wanted) in overrides) {
            if (wanted <= 0) {
                forcedSteps.remove(manifestation)
                continue
            }
            forcedSteps[manifestation] = manifestation.dials.associateWith { dial ->
                wanted.coerceAtMost(laddered.getValue(manifestation)[dial] ?: wanted)
            }
        }
        return Spending(forcedSteps, laddered)
    }

    override fun toString(): String {
        if (steps.isEmpty()) return "nothing bought"
        return steps.entries.joinToString { (manifestation, dials) ->
            val single = manifestation.dials.size == 1
            if (single) "${manifestation.key}×${dials.values.sum()}"
            else "${manifestation.key}(${dials.entries.joinToString { "${it.key}×${it.value}" }})"
        }
    }

    companion object {
        /** A coherent Age, which buys nothing. */
        val NOTHING = Spending(emptyMap())

        /** Strange, harmful, failing (Jonah, 2026-10-12): seen only, hurting or reshaping, and undoing the Age. */
        val TIERS = 1..3

        /** Visual only — at worst a nuisance, like a fog you cannot see through. */
        const val STRANGE = 1

        /** What can hurt you directly, or alter the land. */
        const val HARMFUL = 2

        /** What destroys an Age, left unchecked. */
        const val FAILING = 3

        /** A book this short stays strange however wrong it is: a bare desk's page limit. */
        const val PAGES_KEPT_STRANGE = 5

        /**
         * The highest tier [flaws] distinct flaws reach in a book of [pages] (Jonah): up to three stay strange,
         * four to six harm, and seven or more fail.
         */
        fun topTier(flaws: Int, pages: Int): Int = when {
            pages <= PAGES_KEPT_STRANGE -> STRANGE
            flaws <= MOST_STRANGE -> STRANGE
            flaws <= MOST_HARMFUL -> HARMFUL
            else -> FAILING
        }

        private const val MOST_STRANGE = 3
        private const val MOST_HARMFUL = 6

        /**
         * How many of [flaws] each tier from strange up to [top] is dealt, by tier: **in proportion to how dangerous
         * the tier is** (Jonah, 2026-10-21) — strange one share, harmful two, failing three — and what rounding leaves
         * over to the most dangerous first. Fifteen failing flaws are two strange, five harmful and eight failing,
         * where an even deal spent a third of a collapsing Age on deepening what is only seen.
         */
        fun slotsPerTier(flaws: Int, top: Int): Map<Int, Int> {
            val tiers = (STRANGE..top).toList()
            val shares = tiers.sum()
            val owed = tiers.associateWith { tier -> flaws.toDouble() * tier / shares }
            val dealt = owed.mapValues { (_, share) -> floor(share).toInt() }.toMutableMap()
            val over = flaws - dealt.values.sum()
            // Largest fraction first, the more dangerous tier where two are level.
            val nearestToAnother = tiers.sortedWith(compareByDescending<Int> { owed.getValue(it) % 1.0 }.thenByDescending { it })
            nearestToAnother.take(over).forEach { tier -> dealt[tier] = dealt.getValue(tier) + 1 }
            return dealt
        }

        /** What [instability] buys from these [ladders], for the Age whose seed is [seed] and setting is [setting]. */
        fun of(
            instability: Instability,
            seed: Long,
            ladders: Map<Manifestation, Ladder>,
            setting: SpendingSetting = SpendingSetting.UNKNOWN,
        ): Spending {
            val flaws = instability.index
            if (flaws <= 0) return NOTHING
            fun ladderOf(manifestation: Manifestation) = ladders[manifestation] ?: Ladder.ordinaryFor(manifestation)

            val random = XoroshiroRandomSource(seed xor DRAW_SALT)
            val leanings = Manifestation.entries.associateWith { manifestation ->
                val ladder = ladderOf(manifestation)
                leaningFrom(random.nextDouble()) * climateLean(ladder, setting) * ladder.weight
            }
            val bought = mutableMapOf<Manifestation, MutableMap<String, Int>>()

            fun nextStepOf(manifestation: Manifestation, dial: String): Int = bought[manifestation]?.get(dial) ?: 0

            fun isShutOut(manifestation: Manifestation): Boolean {
                val ladder = ladderOf(manifestation)
                val needsWhatIsNotThere = !setting.has.containsAll(ladder.needs)
                val barredByWhatCame = bought.keys.any { other ->
                    other in ladder.excludes || manifestation in ladderOf(other).excludes
                }
                val theBookAlreadySaid = ladder.heldBy.any { it in setting.written }
                return needsWhatIsNotThere || barredByWhatCame || theBookAlreadySaid
            }

            fun dialsOpenAt(manifestation: Manifestation, tier: Int): List<String> =
                manifestation.dials.filter { dial ->
                    val steps = ladderOf(manifestation).dials[dial] ?: return@filter false
                    val next = nextStepOf(manifestation, dial)
                    next < steps.most && steps.tiers[next] == tier
                }

            /**
             * One step at [tier], or the tier under it where nothing is left at this one — **most often deepening
             * what is already bought** there, so an Age coming apart shows a few things strongly rather than every
             * one of them a little, and two collapsing Ages differ.
             */
            fun buyOneAt(tier: Int) {
                for (trying in tier downTo STRANGE) {
                    val open = Manifestation.entries.filterNot(::isShutOut)
                        .associateWith { dialsOpenAt(it, trying) }
                        .filterValues { it.isNotEmpty() }
                    if (open.isEmpty()) continue
                    val deepenable = open.keys.filter { it in bought }
                    val deepens = deepenable.isNotEmpty() && random.nextDouble() < DEEPENS_WHAT_IT_HAS
                    val among = if (deepens) deepenable else open.keys.toList()
                    val manifestation = pickByLeaning(among, leanings, random.nextDouble())
                    val dials = open.getValue(manifestation)
                    val dial = dials[random.nextInt(dials.size)]
                    bought.getOrPut(manifestation) { mutableMapOf() }[dial] = nextStepOf(manifestation, dial) + 1
                    return
                }
            }

            val top = topTier(flaws, instability.pages)
            val slots = slotsPerTier(flaws, top)
            // **The top tier's own step first**, so no exclusion a lower one drew can crowd it out; then the
            // rest from the bottom up, so a dial that climbs — a deluge's height — has its lower steps
            // bought by the time a higher slot asks for the next one.
            buyOneAt(top)
            for ((tier, count) in slots) repeat(count - if (tier == top) 1 else 0) { buyOneAt(tier) }

            // A collapse brings its companions, over what the flaws bought, wherever the Age has what they need.
            if (Manifestation.COLLAPSE in bought) {
                for (companion in COMES_WITH_COLLAPSE) {
                    val canShowHere = setting.has.containsAll(ladderOf(companion).needs)
                    val firstDial = companion.dials.firstOrNull { dial -> (ladderOf(companion).dials[dial]?.most ?: 0) > 0 }
                    if (companion in bought || !canShowHere || firstDial == null) continue
                    bought[companion] = mutableMapOf(firstDial to 1)
                }
            }

            val ceilings = Manifestation.entries.associateWith { manifestation ->
                ladderOf(manifestation).dials.mapValues { it.value.most }
            }
            return Spending(bought, ceilings)
        }

        /** The same, for a recipe — which is where every caller actually starts. */
        fun of(server: MinecraftServer, recipe: AgeRecipe): Spending {
            val drawnSoFar = alreadyDrawn.of(server)
            if (drawnSoFar.size > MOST_REMEMBERED) drawnSoFar.clear()
            val setting = SpendingSetting.of(server, recipe.composition, recipe.template, recipe.seed)
            val drawn = drawnSoFar.computeIfAbsent(Drawn(recipe.instability.index, recipe.instability.pages, recipe.seed, setting)) {
                of(recipe.instability, recipe.seed, Ladder.list(server), setting)
            }
            return drawn.forced(recipe.forced, Ladder.list(server))
        }

        /** What a draw depends on, and so what one is remembered by. */
        private data class Drawn(val flaws: Int, val pages: Int, val seed: Long, val setting: SpendingSetting)

        /**
         * Held per datapack load because the tick asks for every Age's spending every tick, and a draw always
         * comes out the same.
         */
        private val alreadyDrawn = PerReload { ConcurrentHashMap<Drawn, Spending>() }

        /** Far more Ages and desk readings than a server holds at once; past it the memo starts again. */
        private const val MOST_REMEMBERED = 1024

        /**
         * How much [ladder]'s leans favour it in this [setting]: one where it leans nowhere or the climate is
         * even, more where the Age is what it leans towards, less where it is the opposite.
         */
        private fun climateLean(ladder: Ladder, setting: SpendingSetting): Double {
            val agreement = ladder.leans.entries.sumOf { (axis, lean) -> lean * (setting.climate[axis] ?: 0.0) }
            return exp(CLIMATE_LEAN_STRENGTH * agreement)
        }

        /**
         * One Age's lean toward one manifestation, from a uniform [draw].
         *
         * Exponential, then sharpened, so most Ages lean hard on a few manifestations and lightly on the
         * rest rather than evenly on everything.
         */
        private fun leaningFrom(draw: Double): Double =
            (-ln(1.0 - draw)).pow(LEANING_SHARPNESS).coerceAtLeast(SLIGHTEST_LEANING)

        private fun pickByLeaning(
            among: List<Manifestation>,
            leanings: Map<Manifestation, Double>,
            draw: Double,
        ): Manifestation {
            val total = among.sumOf { leanings.getValue(it) }
            var left = draw * total
            for (manifestation in among) {
                left -= leanings.getValue(manifestation)
                if (left < 0.0) return manifestation
            }
            return among.last()
        }

        /** Squares the exponential, so the heaviest leaning is typically several times the median. */
        private const val LEANING_SHARPNESS = 2.0

        /** How often a step goes to deepen something already bought, where something is. */
        private const val DEEPENS_WHAT_IT_HAS = 0.8

        /**
         * What a collapse always comes with (Jonah, 2026-10-21): the ground giving way under itself and the wounds going
         * on opening, beside the floor's own fissures widening.
         */
        val COMES_WITH_COLLAPSE = listOf(Manifestation.TECTONICS, Manifestation.WORSENING_WOUNDS)

        /** So no manifestation is ever out of the draw entirely, however an Age leans. */
        private const val SLIGHTEST_LEANING = 0.01

        /**
         * How hard the climate leans the draw: a manifestation leaning fully into an Age at the far end of an
         * axis is e³ — some twenty times — likelier than one leaning nowhere.
         */
        private const val CLIMATE_LEAN_STRENGTH = 3.0

        private const val DRAW_SALT = 0x5E3D_1A6B_77C4_0F29L
    }
}

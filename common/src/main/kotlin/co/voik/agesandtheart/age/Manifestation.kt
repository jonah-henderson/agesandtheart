package co.voik.agesandtheart.age

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.datapack.PerReload
import co.voik.agesandtheart.datapack.ResourceParsing
import com.mojang.datafixers.util.Either
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.server.MinecraftServer
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.util.StringRepresentable
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
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

/**
 * A way an Age shows what is wrong with it — **the things instability is spent on** (design §5.0).
 *
 * Each has one or more **dials**, and each dial sells steps. The index is spent on steps drawn off the
 * Age's seed, so two Ages at the same index come apart differently; see [Spending].
 */
enum class Manifestation(val key: String, vararg dials: String) : StringRepresentable {
    /**
     * The seams between territories tear rather than meet: scarps throw further, rifts cut deeper, walls
     * stand higher, and a dissolve widens from a transition into two worlds failing to decide.
     *
     * **`Seam.SHEARED` is exempt and costs nothing** (Jonah, 2026-08-07): terrain on either side of a shear
     * is usually dramatic enough that the two never read as meeting smoothly, so it is instability already
     * visible without paying for it.
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
     * once the further the budget reaches.
     *
     * **Puts a *phenomenon* into an Age nobody wrote one into, and that is the point rather than a leak**
     * (Jonah, 2026-08-31): a column walking a world that never asked for one is exactly what the index is
     * for (§7.7). Where the Age already has a sandfall, the two **compound**.
     *
     * **Four dials** (Jonah, 2026-09-23): how often a column comes and how many may stand at once, how long
     * one lives, how wide it grows, and how deep it buries. The first two are nuisance and cheap; the last
     * two are what takes a base and are dear.
     */
    SANDFALL("sandfall", SANDFALL_OFTEN, SANDFALL_LONG, SANDFALL_SIZE, SANDFALL_DEPTH),

    /**
     * The Age is driven under snow (design §5.2's blizzard). **Four dials** (Jonah, 2026-09-23): how often a
     * storm comes, how long each lasts, how far you can see in one (and how hard the snow drifts), and how
     * fast the cold gets into you. Only the last one hurts, and it is the dear one.
     */
    BLIZZARD("blizzard", BLIZZARD_OFTEN, BLIZZARD_LONG, BLIZZARD_VISIBILITY, BLIZZARD_FROSTBITE),

    /**
     * The sky falls on the Age in showers (design §5.2). **Three dials, priced apart** (Jonah, 2026-09-23):
     * how often a storm gathers and how long it lasts are cheap, and how hard its bodies land is dear. How
     * many bodies a storm drops follows how long it lasts and nothing else, which is what keeps the sky's
     * lights affordable.
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
     * 2026-09-23): how often bolts come is cheap; how big a crater one digs and how much it sets alight are
     * dear. A lightning rod still grounds a bolt, whatever was bought.
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
     * **Three dials, priced apart** (Jonah, 2026-09-17), because the danger is slow to pay off: how fast
     * the sea climbs while it rains and how much of the time it rains are cheap, so an Age can earn a
     * near-constant deluge early; how far the sea climbs opens step by step toward the collapse threshold,
     * so only an Age close to coming apart drowns to the build limit.
     */
    DELUGE("deluge", DELUGE_RATE, DELUGE_DOWNPOUR, DELUGE_HEIGHT),

    /**
     * The world comes apart at the bottom (design §5.3) — fissures open at the world floor and widen, and
     * what falls in is put back in the overworld.
     *
     * **A threshold rather than a draw** (Jonah, 2026-09-17): an Age at or past its price's
     * [Price.certainFrom] collapses, whatever else it drew. What is past the threshold buys how fast the
     * tear spreads.
     */
    COLLAPSE("collapse", "spread"),
    ;

    /** What this manifestation's price sells steps of, in the order its price file is read. */
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
    }
}

/**
 * What one dial sells: a price for each step, and the index each step opens at.
 *
 * Written as either one number for every step (`"costs": 3, "most": 10`) or a list with one entry per
 * step (`"costs": [3, 4, 6]`); `opens_at` is read the same way and defaults to zero.
 */
data class DialPrice(val costs: List<Int>, val opensAt: List<Int>) {
    init {
        require(costs.size == opensAt.size) { "a dial has ${costs.size} prices and ${opensAt.size} floors" }
    }

    /** How many steps it sells. */
    val most: Int get() = costs.size

    companion object {
        /** [most] steps at [costs] each, all open from the start. */
        fun flat(costs: Int, most: Int, opensAt: Int = 0) = DialPrice(List(most) { costs }, List(most) { opensAt })

        private val ONE_OR_EACH: Codec<Either<Int, List<Int>>> = Codec.either(Codec.INT, Codec.INT.listOf())

        /** The file's own shape, before a single number is spread over every step. */
        private data class Written(
            val costs: Either<Int, List<Int>>,
            val most: Optional<Int>,
            val opensAt: Either<Int, List<Int>>,
        )

        private val WRITTEN: Codec<Written> = RecordCodecBuilder.create { instance ->
            instance.group(
                ONE_OR_EACH.fieldOf("costs").forGetter(Written::costs),
                Codec.INT.optionalFieldOf("most").forGetter(Written::most),
                ONE_OR_EACH.optionalFieldOf("opens_at", Either.left(0)).forGetter(Written::opensAt),
            ).apply(instance, ::Written)
        }

        private fun read(written: Written): DataResult<DialPrice> {
            val listed = written.costs.right().orElse(null)
            val steps = listed?.size ?: written.most.orElse(-1)
            if (steps < 0) return DataResult.error { "a single price needs \"most\" to say how many steps it sells" }
            if (listed != null && written.most.isPresent && written.most.get() != listed.size) {
                return DataResult.error { "\"most\" is ${written.most.get()} but ${listed.size} prices are listed" }
            }
            val costs = listed ?: List(steps) { written.costs.left().orElseThrow() }
            val floors = written.opensAt.map({ floor -> List(steps) { floor } }, { it })
            if (floors.size != steps) {
                return DataResult.error { "${floors.size} \"opens_at\" floors for $steps steps" }
            }
            return DataResult.success(DialPrice(costs, floors))
        }

        private fun write(price: DialPrice) =
            Written(Either.right(price.costs), Optional.empty(), Either.right(price.opensAt))

        val CODEC: Codec<DialPrice> = WRITTEN.comapFlatMap(::read, ::write)
    }
}

/**
 * What one manifestation costs, and how far it goes — **datapack content** (`art/manifestation/<key>.json`).
 *
 * Prices are content rather than config (`notes/config-research.md`): a pack that makes tearing cheap is a
 * pack where every clumsy Age comes apart, which is a different mod rather than the same one run
 * differently. And the whole price list being one directory is the tuning surface §5.0 asks for.
 */
data class Price(
    /** What each of the manifestation's dials sells, by the dial's name. */
    val dials: Map<String, DialPrice>,
    /**
     * The index at which this manifestation is bought outright rather than drawn, or null for one that is
     * drawn. Its first step comes with the threshold, and each further step is paid for out of what the
     * index has past it.
     */
    val certainFrom: Int? = null,
) {
    companion object {
        private const val DEFAULT_COSTS = 2
        private const val DEFAULT_MOST = 4

        /** What a manifestation nobody priced costs — so an absent file is a default, never a free one. */
        fun ordinaryFor(manifestation: Manifestation) =
            Price(manifestation.dials.associateWith { DialPrice.flat(DEFAULT_COSTS, DEFAULT_MOST) })

        /** [most] steps at [costs] on every dial [manifestation] has — the whole of a simple price. */
        fun flat(manifestation: Manifestation, costs: Int, most: Int, opensAt: Int = 0) =
            Price(manifestation.dials.associateWith { DialPrice.flat(costs, most, opensAt) })

        val CODEC: Codec<Price> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.unboundedMap(Codec.STRING, DialPrice.CODEC).fieldOf("dials").forGetter(Price::dials),
                Codec.INT.optionalFieldOf("certain_from").forGetter { Optional.ofNullable(it.certainFrom) },
            ).apply(instance) { dials, certainFrom -> Price(dials, certainFrom.orElse(null)) }
        }

        const val DIRECTORY = "art/manifestation"

        /** The price list this server is running, read once per datapack load. */
        fun list(server: MinecraftServer): Map<Manifestation, Price> = current.of(server)

        private val current = PerReload { server -> load(server.resourceManager) }

        /** The price list in [resources] — the whole of the loading, and usable offline. */
        fun load(resources: ResourceManager): Map<Manifestation, Price> {
            val problems = mutableListOf<String>()
            val prices = buildMap {
                for (manifestation in Manifestation.entries) put(manifestation, ordinaryFor(manifestation))
                for ((file, resource) in resources.listResources(DIRECTORY, ResourceParsing::isJson)) {
                    val key = ResourceParsing.nameUnder(file, DIRECTORY)
                    val named = Manifestation.entries.firstOrNull { it.key == key }
                    if (named == null) {
                        problems += "$file names no manifestation, so nothing reads it"
                        continue
                    }
                    val price = ResourceParsing.parse(resource, file, CODEC, problems) ?: continue
                    put(named, fittedTo(named, price, file.toString(), problems))
                }
            }
            for (problem in problems) Constants.LOG.warn("Price list: {}", problem)
            return prices
        }

        /** [price] with exactly [manifestation]'s dials: a misspelt one dropped, a missing one priced ordinarily. */
        private fun fittedTo(
            manifestation: Manifestation,
            price: Price,
            file: String,
            problems: MutableList<String>,
        ): Price {
            for (unread in price.dials.keys - manifestation.dials.toSet()) {
                problems += "$file prices a dial '$unread' that ${manifestation.key} does not have"
            }
            val dials = manifestation.dials.associateWith { dial ->
                price.dials[dial] ?: DialPrice.flat(DEFAULT_COSTS, DEFAULT_MOST).also {
                    problems += "$file does not price ${manifestation.key}'s '$dial', so it costs the default"
                }
            }
            return price.copy(dials = dials)
        }
    }
}

/**
 * What an Age's instability actually bought (design §5.0).
 *
 * **Derived, never stored.** A pure function of the index, the seed and the prices, so it comes out the same
 * on every open and needs no room in the recipe.
 *
 * **Drawn rather than ordered** (Jonah, 2026-09-17). Each Age leans toward some manifestations more than
 * others, drawn off its seed, and the index is spent a step at a time on whatever it leans toward and can
 * afford. The same index therefore shows as different symptoms in different Ages, and an Age that leans
 * hard on one thing gets a lot of it early. A step's `opens_at` floor is what keeps the verdicts back —
 * nothing the draw does can buy a step before the index reaches it.
 */
data class Spending(
    private val steps: Map<Manifestation, Map<String, Int>>,
    /** How many steps each priced dial sells, at the prices this was bought at. */
    private val ceilings: Map<Manifestation, Map<String, Int>> = emptyMap(),
) {

    /** How many steps of [manifestation]'s [dial] were bought. */
    fun bought(manifestation: Manifestation, dial: String): Int = steps[manifestation]?.get(dial) ?: 0

    /** How many steps of [manifestation] were bought across all its dials — zero where the budget never reached it. */
    fun bought(manifestation: Manifestation): Int = steps[manifestation]?.values?.sum() ?: 0

    /**
     * How far into [manifestation]'s [dial] this Age went, from nothing to everything it sells — the number
     * a phenomenon scales by, so a caller never has to know what a step cost.
     */
    fun reach(manifestation: Manifestation, dial: String): Double {
        val most = ceilings[manifestation]?.get(dial) ?: return 0.0
        if (most <= 0) return 0.0
        return bought(manifestation, dial).toDouble() / most
    }

    /** How far into [manifestation] this Age went, averaged over its dials — for one dial, that dial's reach. */
    fun reach(manifestation: Manifestation): Double =
        manifestation.dials.sumOf { reach(manifestation, it) } / manifestation.dials.size

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

        /**
         * What [budget] buys at these [prices], for the Age whose seed is [seed].
         *
         * Every manifestation with a [Price.certainFrom] is settled first and costs the draw nothing. The
         * rest are drawn: each round picks a manifestation by the Age's leaning among those with a step it
         * can afford and has opened, then one of that manifestation's open dials evenly, and pays for the
         * next step. It ends when nothing is left that the remainder can buy.
         */
        fun of(budget: Int, seed: Long, prices: Map<Manifestation, Price>): Spending {
            if (budget <= 0) return NOTHING
            fun priceOf(manifestation: Manifestation) = prices[manifestation] ?: Price.ordinaryFor(manifestation)

            val bought = mutableMapOf<Manifestation, MutableMap<String, Int>>()
            val ceilings = Manifestation.entries.associateWith { manifestation ->
                priceOf(manifestation).dials.mapValues { it.value.most }
            }
            for (manifestation in Manifestation.entries) {
                val price = priceOf(manifestation)
                val from = price.certainFrom ?: continue
                if (budget < from) continue
                bought[manifestation] = price.dials.mapValuesTo(mutableMapOf()) { (_, dial) ->
                    stepsPastTheThreshold(dial, budget - from)
                }
            }

            val drawn = Manifestation.entries.filter { priceOf(it).certainFrom == null }
            val random = XoroshiroRandomSource(seed xor DRAW_SALT)
            val leanings = drawn.associateWith { leaningFrom(random.nextDouble()) }
            var remaining = budget
            while (true) {
                fun nextStepOf(manifestation: Manifestation, dial: String): Int =
                    bought[manifestation]?.get(dial) ?: 0

                fun canBuyTheNextStep(manifestation: Manifestation, dial: String): Boolean {
                    val price = priceOf(manifestation).dials[dial] ?: return false
                    val step = nextStepOf(manifestation, dial)
                    if (step >= price.most) return false
                    val cost = price.costs[step]
                    val isFree = cost <= 0
                    val isAffordable = cost <= remaining
                    val isOpen = price.opensAt[step] <= budget
                    return !isFree && isAffordable && isOpen
                }

                val open = drawn.associateWith { manifestation ->
                    manifestation.dials.filter { canBuyTheNextStep(manifestation, it) }
                }.filterValues { it.isNotEmpty() }
                if (open.isEmpty()) break

                val manifestation = pickByLeaning(open.keys.toList(), leanings, random.nextDouble())
                val dials = open.getValue(manifestation)
                val dial = dials[random.nextInt(dials.size)]
                val step = nextStepOf(manifestation, dial)
                remaining -= priceOf(manifestation).dials.getValue(dial).costs[step]
                bought.getOrPut(manifestation) { mutableMapOf() }[dial] = step + 1
            }
            val anything = bought.filterValues { dials -> dials.values.any { it > 0 } }
            return Spending(anything, ceilings)
        }

        /** The same, for a recipe — which is where every caller actually starts. */
        fun of(server: MinecraftServer, recipe: AgeRecipe): Spending {
            val drawnSoFar = alreadyDrawn.of(server)
            if (drawnSoFar.size > MOST_REMEMBERED) drawnSoFar.clear()
            return drawnSoFar.computeIfAbsent(recipe.instability.index to recipe.seed) { (index, seed) ->
                of(index, seed, Price.list(server))
            }
        }

        /**
         * Held per datapack load because the tick asks for every Age's spending every tick, and a draw is a
         * few hundred rounds of arithmetic that always comes out the same.
         */
        private val alreadyDrawn = PerReload { ConcurrentHashMap<Pair<Int, Long>, Spending>() }

        /** Far more Ages and desk readings than a server holds at once; past it the memo starts again. */
        private const val MOST_REMEMBERED = 1024

        /** How many of [dial]'s steps a certain manifestation has with [pastIt] of the index beyond its threshold. */
        private fun stepsPastTheThreshold(dial: DialPrice, pastIt: Int): Int {
            if (dial.most == 0) return 0
            var remaining = pastIt
            var steps = 1
            while (steps < dial.most && dial.costs[steps] in 1..remaining) {
                remaining -= dial.costs[steps]
                steps++
            }
            return steps
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

        /** So no manifestation is ever out of the draw entirely, however an Age leans. */
        private const val SLIGHTEST_LEANING = 0.01

        private const val DRAW_SALT = 0x5E3D_1A6B_77C4_0F29L
    }
}

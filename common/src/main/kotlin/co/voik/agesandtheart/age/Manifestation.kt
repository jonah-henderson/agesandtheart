package co.voik.agesandtheart.age

import co.voik.agesandtheart.Constants
import com.google.gson.JsonParser
import com.mojang.serialization.Codec
import com.mojang.serialization.JsonOps
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.server.MinecraftServer
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.util.StringRepresentable

/**
 * A way an Age shows what is wrong with it — **the things instability is spent on** (design §5.0).
 *
 * The index is a budget rather than a severity dial, so each of these has a price and an Age buys what it
 * can afford. A small mistake widens the seams and can reach nothing else; blight and collapse are simply
 * unaffordable until a writer has earned them, which fences the dire registers by arithmetic rather than
 * by a guard written into each.
 */
enum class Manifestation(val key: String) : StringRepresentable {
    /**
     * The seams between territories tear rather than meet: scarps throw further, rifts cut deeper, walls
     * stand higher, and a dissolve widens from a transition into two worlds failing to decide.
     *
     * The cheapest, and the only one built. **`Seam.SHEARED` is exempt and costs nothing** (Jonah,
     * 2026-08-07): terrain on either side of a shear is usually dramatic enough that the two never read as
     * meeting smoothly, so it is instability already visible without paying for it — and an Age that drew
     * one has its whole budget left for something else.
     */
    TORN_SEAMS("torn_seams"),

    /**
     * Tears in spacetime open in the ground (design §5.1) — small, unlit, and impossible to do anything
     * with but wall in.
     *
     * Dearer than a torn seam, because a seam is the world's shape being wrong where this is the world
     * being *holed*. What a step buys is **how often one opens**, as a chance per chunk: a count would be
     * a handful nobody ever walks past, where a frequency is a thing you meet while doing something else.
     */
    WOUNDS("wounds"),

    /**
     * The tearing does not stop (design §5.2.1) — the Age goes on holing itself for as long as it exists.
     *
     * **A verdict rather than a fight.** What a step buys is how *fast* the wound density climbs with the
     * Age's age, so [WOUNDS] is how holed it was written and this is how holed it becomes. Unbounded on
     * purpose: a ceiling would promise the Age can be outlasted, and the only question this register asks
     * is how long you stay.
     */
    WORSENING_WOUNDS("worsening_wounds"),

    /**
     * Columns of sand walk the Age (design §5.2.2) — bigger, hungrier, longer-lived and more of them at
     * once the further the budget reaches.
     *
     * **The first manifestation that puts a *phenomenon* into an Age nobody wrote one into, and that is the
     * point rather than a leak** (Jonah, 2026-08-31). Instability is the way an Age comes apart
     * unpredictably, so a column walking a world that never asked for one is exactly what the index is for
     * (§7.7's "a meteor storm you introduced *by contradiction*"). Nothing here tests whether the Age
     * already has a sandfall: where it does, the two **compound**.
     *
     * **Priced below [WORSENING_WOUNDS] deliberately.** A sandfall is difficulty and worsening is a
     * verdict — you can roof over sand, and burial hands you the glass to do it with, where an Age that
     * goes on holing itself only asks how long you stay. Consequence should reach the hazard you can
     * answer before the one you cannot.
     */
    SANDFALL("sandfall"),

    /**
     * The Age is driven under snow (design §5.2's blizzard) — storms that come more often and stay longer
     * the further the budget reaches.
     *
     * **Priced beside [SANDFALL] and for the same reason**: it is difficulty you can answer rather than a
     * verdict you cannot. Light stops the snow settling and leather stops the freezing, both of them
     * vanilla's own rules, so an Age that keeps holing itself is still the dearer thing.
     *
     * **What a step buys is weather, not violence** — how often a blizzard blows and how long it stays.
     * At the bottom that is about as often as ordinary rain and a few minutes of it; at the top the Age is
     * scarcely ever out of one. The burial that follows is the hazard, and it is meant to be.
     */
    BLIZZARD("blizzard"),

    /**
     * The sky falls on the Age in showers (design §5.2) — storms that come more often, last longer and
     * hit harder the further the budget reaches.
     *
     * **Priced beside [SANDFALL] and [BLIZZARD]**, and for the same reason all three share: it is
     * difficulty you can answer rather than a verdict you cannot. A roof of anything in
     * `#agesandtheart:seals_wounds` is immune at any power worth using, and being properly indoors is
     * free — so an Age that keeps holing itself is still the dearer thing.
     *
     * **What a step buys is all three of its dials at once**, which no other manifestation does: more
     * storms, longer ones, and bodies coming in harder. A shower that only came more often would read as
     * the bottom of the ramp repeated.
     */
    METEORS("meteors"),

    /**
     * The Age is set alight (design §5.2) — the sun scours frost off the ground and lights whatever will
     * burn, in a world that was never written to be fiery.
     *
     * **One step and no ramp, deliberately** (Jonah, 2026-09-09, adding it to the list). The three above
     * it each buy a designed ramp — how often, how long, how hard — and an inferno has none: what a
     * written one does is a fact about the sun, not a dial. So instability either sets the Age alight or
     * it does not, which is a complete thing to say rather than a ramp invented to match its neighbours.
     * If the instability pass wants a ramp, `Inferno.burn` is where one would have to be designed first.
     *
     * **It is difficulty you can answer, like its neighbours**, and more sharply than most: rain puts fire
     * out by vanilla's own rule, so an inflicted inferno in a wet Age is a nuisance and in a dry one is a
     * siege. That an Age's own climate decides which is the best thing about it, and none of it is written
     * here.
     */
    INFERNO("inferno"),

    /**
     * The world comes apart at the bottom (design §5.3) — fissures open at the world floor and widen, and
     * what falls in is put back in the overworld.
     *
     * The dearest thing in the list, and the only one that ends the Age. A step buys how fast the tear
     * spreads, which is why an Age that can afford this is one you visit rather than live in.
     */
    COLLAPSE("collapse"),
    ;

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<Manifestation> = StringRepresentable.fromEnum(Manifestation::values)
    }
}

/**
 * What one manifestation costs, and how far it goes — **datapack content** (`art/manifestation/<key>.json`).
 *
 * Prices are content rather than config (`notes/config-research.md`): a pack that makes tearing cheap is a
 * pack where every clumsy Age comes apart, which is a different mod rather than the same one run
 * differently. And the whole price list being one directory is the tuning surface §5.0 asks for — the
 * alternative is a threshold buried in each register, which is where calibration drifts.
 */
data class Price(
    /** What one step of it costs out of the budget. */
    val costs: Int = DEFAULT_COSTS,
    /**
     * How many steps may be bought at all.
     *
     * A cap rather than a slope, so a runaway index cannot buy an unbounded amount of any one thing — the
     * budget moves on to the next manifestation instead, which is what makes consequence read as
     * accumulation (§5.0).
     */
    val most: Int = DEFAULT_MOST,
) {
    companion object {
        private const val DEFAULT_COSTS = 2
        private const val DEFAULT_MOST = 4

        /** What a manifestation nobody priced costs — so an absent file is a default, never a free one. */
        val ORDINARY = Price()

        val CODEC: Codec<Price> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.optionalFieldOf("costs", DEFAULT_COSTS).forGetter(Price::costs),
                Codec.INT.optionalFieldOf("most", DEFAULT_MOST).forGetter(Price::most),
            ).apply(instance, ::Price)
        }

        const val DIRECTORY = "art/manifestation"

        /** The price list this server is running, cached on the resource manager exactly as the corpus is. */
        fun list(server: MinecraftServer): Map<Manifestation, Price> {
            val resources = server.resourceManager
            loaded?.let { (from, known) -> if (from === resources) return known }
            return read(resources).also { loaded = resources to it }
        }

        private var loaded: Pair<ResourceManager, Map<Manifestation, Price>>? = null

        private fun read(resources: ResourceManager): Map<Manifestation, Price> = buildMap {
            for (manifestation in Manifestation.entries) put(manifestation, ORDINARY)
            for ((file, resource) in resources.listResources(DIRECTORY) { it.path.endsWith(SUFFIX) }) {
                val key = file.path.removePrefix("$DIRECTORY/").removeSuffix(SUFFIX)
                val named = Manifestation.entries.firstOrNull { it.key == key }
                if (named == null) {
                    Constants.LOG.warn("'{}' names no manifestation, so nothing reads it", file)
                    continue
                }
                val read = runCatching {
                    resource.open().use { CODEC.parse(JsonOps.INSTANCE, JsonParser.parseReader(it.reader())).getOrThrow() }
                }
                read.onFailure { Constants.LOG.warn("Could not read '{}': {}", file, it.message) }
                read.getOrNull()?.let { put(named, it) }
            }
        }

        private const val SUFFIX = ".json"
    }
}

/**
 * What an Age's instability actually bought (design §5.0).
 *
 * **Derived, never stored.** A pure function of the instability and the prices, so it comes out the same on
 * every open and needs no room in the recipe — which keeps generation a pure function of the recipe, and
 * keeps the recipe a record of what was *written* rather than of what can be inferred from it.
 *
 * **Cheapest first**, so a large budget buys the small manifestations *as well as* the large: an Age that
 * can afford to collapse also has torn seams, because it paid for those on the way. Consequence reads as
 * accumulation rather than as a threshold crossed into a different world.
 */
data class Spending(private val steps: Map<Manifestation, Int>) {

    /** How many steps of [manifestation] were bought — zero where the budget never reached it. */
    fun bought(manifestation: Manifestation): Int = steps[manifestation] ?: 0

    /**
     * How far into [manifestation] this Age went, from nothing at all to everything it could buy — the
     * number a generator scales by, so a caller never has to know what a step cost.
     */
    fun reach(manifestation: Manifestation, prices: Map<Manifestation, Price>): Double {
        val most = prices[manifestation]?.most ?: Price.ORDINARY.most
        if (most <= 0) return 0.0
        return bought(manifestation).toDouble() / most
    }

    override fun toString(): String =
        if (steps.isEmpty()) "nothing bought" else steps.entries.joinToString { "${it.key.key}×${it.value}" }

    companion object {
        /** A coherent Age, which buys nothing. */
        val NOTHING = Spending(emptyMap())

        /**
         * What [budget] buys at these [prices].
         *
         * The order is the price list's own, cheapest first and ties broken by declaration, so the
         * allocation is a pure function of its inputs and does not depend on a map's iteration order.
         */
        fun of(budget: Int, prices: Map<Manifestation, Price>): Spending {
            if (budget <= 0) return NOTHING
            var remaining = budget
            val steps = mutableMapOf<Manifestation, Int>()
            val cheapestFirst = Manifestation.entries.sortedWith(
                compareBy({ prices[it]?.costs ?: Price.ORDINARY.costs }, Manifestation::ordinal),
            )
            for (manifestation in cheapestFirst) {
                val price = prices[manifestation] ?: Price.ORDINARY
                if (price.costs <= 0) continue
                val affordable = (remaining / price.costs).coerceAtMost(price.most)
                if (affordable <= 0) continue
                steps[manifestation] = affordable
                remaining -= affordable * price.costs
            }
            return Spending(steps)
        }

        /** The same, for a recipe — which is where every caller actually starts. */
        fun of(server: MinecraftServer, recipe: AgeRecipe): Spending =
            of(recipe.instability.index, Price.list(server))
    }
}

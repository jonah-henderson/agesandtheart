package co.voik.agesandtheart.age

import co.voik.agesandtheart.Constants
import com.google.gson.JsonParser
import com.mojang.serialization.Codec
import com.mojang.serialization.JsonOps
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.server.MinecraftServer
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.util.StringRepresentable
import net.minecraft.world.level.levelgen.XoroshiroRandomSource

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
 * **Derived, never stored.** A pure function of the instability and the seed, so it comes out the same on
 * every open and needs no room in the recipe — which keeps generation a pure function of the recipe, and
 * keeps the recipe a record of what was *written* rather than of what can be inferred from it.
 *
 * **Cheapest first**, so a large budget buys the small manifestations *as well as* the large: an Age that
 * can afford to collapse also has torn seams, because it paid for those on the way. Consequence reads as
 * accumulation rather than as a threshold crossed into a different world.
 */
data class Spending(private val steps: Map<Manifestation, Int>, val unspent: Int) {

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
        val NOTHING = Spending(emptyMap(), unspent = 0)

        /**
         * What [budget] buys at these [prices].
         *
         * The order is the price list's own, cheapest first and ties broken by declaration, so the
         * allocation is a pure function of its inputs and does not depend on a map's iteration order.
         * [seed] is taken for the choices that will need it once a manifestation has somewhere to go
         * rather than only a size — a wound has to land *somewhere* (§5.1) — and is deliberately unused
         * while the only manifestation is a magnitude.
         */
        fun of(budget: Int, prices: Map<Manifestation, Price>, @Suppress("UNUSED_PARAMETER") seed: Long): Spending {
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
            return Spending(steps, unspent = remaining)
        }

        /** The same, for a recipe — which is where every caller actually starts. */
        fun of(server: MinecraftServer, recipe: AgeRecipe): Spending =
            of(recipe.instability.index, Price.list(server), recipe.seed)

        /** Kept for the choices a sited manifestation will need, so the salt is decided once. */
        fun randomFor(seed: Long): XoroshiroRandomSource = XoroshiroRandomSource(seed xor SPENDING_SALT)

        private const val SPENDING_SALT = 0x7EA5_1B1EL
    }
}

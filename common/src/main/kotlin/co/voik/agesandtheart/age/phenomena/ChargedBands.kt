package co.voik.agesandtheart.age.phenomena

import java.util.Collections
import java.util.WeakHashMap
import net.minecraft.util.RandomSource
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.XoroshiroRandomSource

/**
 * The heights a charged Age's ore drifts at — **two or three flat layers**, rolled once per Age.
 *
 * Flat rather than terrain-following, deliberately: what these are meant to read as is layers in a field,
 * and a band that hugged the ground would read as weather instead.
 *
 * **The lowest is a signpost and its height is a rendering fact, not an aesthetic one.** It has to be seen
 * from the ground so that *up* reads as the direction to explore, and an entity a couple of hundred blocks
 * away is a speck whatever its tracking range — so the first band sits where a body still reads as an
 * object, and the higher ones are legible as lights rather than shapes.
 *
 * **Nothing carries a band, because the tier is one.** A body's tier decides which of these is its home,
 * which is what makes a fragment sink with no special case at all: it is smaller, so it belongs lower, so
 * it goes there. See [DriftingOre].
 */
object ChargedBands {

    /** Where a body of this tier belongs, in this Age. */
    fun homeFor(level: Level, tier: Int): Double {
        val bands = heightsIn(level)
        return bands[tier.coerceIn(0, bands.size - 1)]
    }

    /**
     * The bands themselves, lowest first.
     *
     * Two or three of them, because an Age that offers only two climbs is a different world from one that
     * offers three and both are worth writing — and a writer who never asked should not always get the
     * same sky.
     */
    fun heightsIn(level: Level): List<Double> = remembered.getOrPut(level) { rolledFor(level) }

    private fun rolledFor(level: Level): List<Double> {
        val random: RandomSource = XoroshiroRandomSource(level.dimension().identifier().hashCode().toLong() xor BAND_SALT)
        val lowest = LOWEST_LEAST + random.nextDouble() * (LOWEST_MOST - LOWEST_LEAST)
        val highest = HIGHEST
        return if (random.nextInt(TWO_IN) == 0) listOf(lowest, highest)
        else listOf(lowest, lowest + (highest - lowest) * MIDDLE_SHARE, highest)
    }

    /**
     * Rolled once per level and kept, because **every body asks this every tick**: a body seeking its band
     * reaches [homeFor] from `DriftingOre.tick`, so a sky of a dozen was building a random source and a
     * list a dozen times a tick on the server and again on every client, for an answer that cannot change
     * while the level exists.
     *
     * Weakly keyed by level for `Wounds`' reason — a world that goes away takes its bands with it, and
     * synchronised because the server thread and a client thread both ask about the same level.
     */
    private val remembered: MutableMap<Level, List<Double>> =
        Collections.synchronizedMap(WeakHashMap<Level, List<Double>>())

    /** How many tiers this Age actually has, which is how many bands it rolled. */
    fun tiersIn(level: Level): Int = heightsIn(level).size

    /**
     * Where the lowest band may sit. **Low enough to be an object from the ground**, which is the whole of
     * its job — and it is why the ladder does not simply divide the build height in three.
     */
    private const val LOWEST_LEAST = 120.0
    private const val LOWEST_MOST = 140.0

    /** And the highest, at the top of the world, where the biggest and richest bodies are. */
    const val HIGHEST = 300.0

    /**
     * How far a body may be from every player before it is forgotten — **a fact about the bands, which is
     * why it lives here** rather than beside the code that discards one.
     *
     * It is asked in three dimensions, and the top band is at the build limit: a body up there is 236
     * blocks from somebody standing at sea level *directly under it*. A radius chosen as a plausible
     * number rather than derived deleted the entire top tier seconds after it spawned. This is the range a
     * client is told about a body at all (`AgeContent.DRIFTING_ORE_TRACKING_CHUNKS`, sixteen chunks), so
     * the rule is simply: forget one when nobody is being sent it.
     */
    const val FORGOTTEN_BEYOND = 256.0

    /** Vanilla's, and what "seen from the ground" is measured against. */
    const val SEA_LEVEL = 64.0

    /** Where a middle band falls between them — a little under half, so the last climb is the long one. */
    private const val MIDDLE_SHARE = 0.45

    /** How often an Age rolls two bands rather than three. */
    private const val TWO_IN = 3

    private const val BAND_SALT = 0x0C_4A_46_5DL
}

package co.voik.agesandtheart.content

import co.voik.agesandtheart.age.phenomena.VolcanicBomb
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Blocks

/**
 * What a lava tube does, and what a connected mass of them is worth (design §7.1.2).
 *
 * A mass wells lava up into the caldera above it, as deep as the mass is tall, and the same mass is what
 * decides how hard the volcano throws. So mining some of it makes a volcano quieter rather than only
 * killing it outright.
 *
 * **Throwing and pouring are separate errands on separate clocks.** A random tick is the right rhythm for
 * an eruption — rare, and a volcano that fired on a schedule would read as a machine — but it is far too
 * slow to fill a caldera, which is thousands of blocks and would take hours at one visit every few
 * seconds. So a random tick throws and *starts* the pour, and the pour then runs on scheduled ticks until
 * the lake is complete and stops paying for itself.
 */
object LavaTubes {

    /**
     * Whether the space over [at] is stopped up.
     *
     * **Tested for something that blocks motion rather than for "not air", and that distinction is the
     * whole mechanic.** A tube's own lava stands directly above it, so a not-air test would have every
     * volcano silence itself on its first success. Lava and air both leave it open; a placed block does
     * not — which is what makes plugging a deliberate act, and what lets water do it for a bucket by
     * turning the lava to obsidian.
     */
    fun plugged(level: BlockGetter, at: BlockPos): Boolean = level.getBlockState(at.above()).blocksMotion()

    /**
     * The connected run of tubes [at] belongs to, six-ways, up to [MOST_IN_A_MASS].
     *
     * Capped because this is asked on a tick and a pathological Age could otherwise hand back a cavern's
     * worth. The cap is generous against what a caldera holds, so it bounds the cost without bounding the
     * design.
     */
    fun massAround(level: BlockGetter, at: BlockPos): Set<BlockPos> {
        val found = linkedSetOf(at)
        val queue = ArrayDeque(listOf(at))
        while (queue.isNotEmpty() && found.size < MOST_IN_A_MASS) {
            val here = queue.removeFirst()
            for (way in Direction.entries) {
                val neighbour = here.relative(way)
                if (neighbour in found) continue
                if (!level.getBlockState(neighbour).`is`(AgeContent.LAVA_TUBE_BLOCK)) continue
                found += neighbour
                queue += neighbour
            }
        }
        return found
    }

    /** How deep a mass wells: its own height, so three tubes stacked make a pool three deep. */
    fun depthOf(mass: Set<BlockPos>): Int {
        val lowest = mass.minOf { it.y }
        val highest = mass.maxOf { it.y }
        return highest - lowest + ONE
    }

    /** Throw something, if this vent is big enough to. */
    fun erupt(level: ServerLevel, at: BlockPos) {
        if (plugged(level, at)) return
        val mass = massAround(level, at)
        val force = forceOf(mass) ?: return
        VolcanicBomb.thrownFrom(level, mouthOver(level, mass.maxByOrNull { it.y } ?: at), force)
    }

    /**
     * Lay some of the lava this mass owes its caldera, up to [POURED_PER_TICK], and say how much went in.
     *
     * **Outward first, then upward**, so a pool spreads across the crater floor before it deepens — which
     * is what makes a wide shallow caldera fill like a lake rather than a column. The spread runs only
     * through what a fluid could occupy, so the crater walls contain it without anything having to know
     * where they are.
     *
     * Lava is placed as **source blocks in a computed volume, never allowed to flow**. Flowing is the
     * expensive half, and a computed pool is idempotent: running this again over a full caldera walks the
     * same space, places nothing, and answers zero — which is what tells the caller to stop.
     */
    fun pour(level: ServerLevel, at: BlockPos): Int {
        if (plugged(level, at)) return NOTHING_POURED
        val mass = massAround(level, at)
        val ceiling = mass.maxOf { it.y } + depthOf(mass)
        // The reach is measured from one point rather than from every block of the mass: a mass is a
        // small disc, so the answers differ by a block or two, and asking sixty-four of them per candidate
        // is the whole cost of a pool that runs to thousands.
        val middleX = mass.sumOf { it.x } / mass.size
        val middleZ = mass.sumOf { it.z } / mass.size
        val seen = HashSet<BlockPos>(mass)
        var frontier = mass.map { it.above() }.filter { it !in mass }
        var poured = 0
        while (frontier.isNotEmpty() && poured < POURED_PER_TICK) {
            val next = mutableListOf<BlockPos>()
            for (position in frontier) {
                if (poured >= POURED_PER_TICK) break
                if (!seen.add(position)) continue
                if (position.y > ceiling) continue
                if (!withinReach(position, middleX, middleZ)) continue
                val state = level.getBlockState(position)
                if (state.blocksMotion()) continue
                if (!state.`is`(Blocks.LAVA)) {
                    level.setBlockAndUpdate(position, Blocks.LAVA.defaultBlockState())
                    poured++
                }
                // Sideways before up, so the frontier finishes a floor before it climbs.
                Direction.Plane.HORIZONTAL.forEach { way -> next += position.relative(way) }
                next += position.above()
            }
            frontier = next
        }
        return poured
    }

    private fun withinReach(position: BlockPos, middleX: Int, middleZ: Int): Boolean {
        val spreadX = (position.x - middleX).toDouble()
        val spreadZ = (position.z - middleZ).toDouble()
        return spreadX * spreadX + spreadZ * spreadZ <= REACH_FROM_THE_MASS * REACH_FROM_THE_MASS
    }

    /**
     * How hard a volcano with this mass throws, or null where it is too small to throw at all.
     *
     * **A critical mass, then a ramp.** Under [ENOUGH_TO_THROW] a vent only seeps: it wells its lava and
     * is otherwise a warm place to stand, which gives a player something to find and read before anything
     * is thrown at them. From there it climbs to full at [MOST_IN_A_MASS], so mining a vent back under the
     * line silences it without having to dig out every last block — the volcano is *tamed* rather than
     * only killed.
     *
     * A share rather than a size, so the curve lives here and what a bomb does with it lives there.
     */
    fun forceOf(mass: Set<BlockPos>): Double? {
        if (mass.size < ENOUGH_TO_THROW) return null
        val over = (mass.size - ENOUGH_TO_THROW).toDouble()
        val span = (MOST_IN_A_MASS - ENOUGH_TO_THROW).toDouble()
        return (over / span).coerceIn(NOTHING, EVERYTHING)
    }

    /**
     * The open air over a vent, above whatever lava is standing on it.
     *
     * **A vent fills its own caldera, so it throws from under its own lake.** Spawning at the tube put
     * every bomb inside lava, where it was quenched by the rule meant to stop the Age flooding — the
     * volcano threw nothing and looked broken. Climbing out of the lava first makes it erupt from the
     * surface, which is both what a volcano does and what lets the quenching rule mean what it should.
     */
    private fun mouthOver(level: ServerLevel, vent: BlockPos): BlockPos {
        var mouth = vent.above()
        var climbed = 0
        while (climbed < MOST_LAVA_OVERHEAD && level.getBlockState(mouth).`is`(Blocks.LAVA)) {
            mouth = mouth.above()
            climbed++
        }
        return mouth
    }

    private const val MOST_IN_A_MASS = 64

    /** Under this a vent only seeps. A quarter of a full mass, so it is a real threshold to cross. */
    private const val ENOUGH_TO_THROW = 16

    /** A pool is capped at the mass's own height, so there is never much more lava than this to climb. */
    private const val MOST_LAVA_OVERHEAD = 8

    /**
     * A caldera floor runs to a couple of thousand blocks and the pour is chained until it is covered, so
     * this is what a tick of that fill costs rather than what the whole lake does.
     */
    private const val POURED_PER_TICK = 128

    /** Far enough to flood the widest caldera floor, short enough that a breached rim does not drain. */
    private const val REACH_FROM_THE_MASS = 22.0

    private const val ONE = 1
    private const val NOTHING_POURED = 0
    private const val NOTHING = 0.0
    private const val EVERYTHING = 1.0
}

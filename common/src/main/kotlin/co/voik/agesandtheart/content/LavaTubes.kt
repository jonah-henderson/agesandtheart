package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.BlockGetter
import co.voik.agesandtheart.age.phenomena.VolcanicBomb
import net.minecraft.world.level.block.Blocks

/**
 * What a lava tube does, and what a connected mass of them is worth (design §7.1.2).
 *
 * A mass wells lava up into the caldera above it, as deep as the mass is tall, and the same mass is what
 * decides how hard the volcano throws. So mining some of it makes a volcano quieter rather than only
 * killing it outright.
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

    /**
     * Lay some of the lava this mass owes its caldera, up to [POURED_PER_TICK].
     *
     * **Outward first, then upward**, so a pool spreads across the crater floor before it deepens — which
     * is what makes a wide shallow caldera fill like a lake rather than a column. The spread runs only
     * through what a fluid could occupy, so the crater walls contain it without anything having to know
     * where they are.
     *
     * Lava is placed as **source blocks in a computed volume, never allowed to flow**. Flowing is the
     * expensive half, and a computed pool is idempotent: running this again over a full caldera walks the
     * same space and places nothing.
     */
    fun wellUp(level: ServerLevel, at: BlockPos) {
        if (plugged(level, at)) return
        val mass = massAround(level, at)
        throwSomething(level, at, mass)
        val ceiling = mass.maxOf { it.y } + depthOf(mass)
        val reach = REACH_FROM_THE_MASS
        val seen = HashSet<BlockPos>(mass)
        var frontier = mass.map { it.above() }.filter { it !in mass }
        var poured = 0
        while (frontier.isNotEmpty() && poured < POURED_PER_TICK) {
            val next = mutableListOf<BlockPos>()
            for (position in frontier) {
                if (poured >= POURED_PER_TICK) break
                if (!seen.add(position)) continue
                if (position.y > ceiling) continue
                if (!mass.any { it.closerThan(position, reach) }) continue
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
     * Throw something, if this vent is big enough.
     *
     * **The random tick IS the rhythm, and a second gate on top of it was too much.** Only the top layer of
     * a mass is ever unplugged — everything under it has a tube overhead — so a caldera offers around a
     * dozen tickable blocks, and a random tick finds one of those every few seconds. Rolling again on top
     * of that put eruptions minutes apart, which reads as a volcano that does not work.
     */
    private fun throwSomething(level: ServerLevel, at: BlockPos, mass: Set<BlockPos>) {
        val force = forceOf(mass) ?: return
        VolcanicBomb.thrownFrom(level, mouthOver(level, mass.maxByOrNull { it.y } ?: at), force)
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

    private const val POURED_PER_TICK = 32

    /**
     * Far enough to flood a caldera floor, short enough that a breached rim does not drain into the world.
     *
     * Widened from 24, which left a pool that did not reach the edges of the crater it sat in. The cones
     * are cut to a caldera radius of 34, so the reach has to clear that or the lake is a puddle in a bowl.
     */
    private const val REACH_FROM_THE_MASS = 40.0

    private const val ONE = 1
    private const val NOTHING = 0.0
    private const val EVERYTHING = 1.0
}

package co.voik.agesandtheart.content

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
     * How hard a volcano with this mass throws.
     *
     * Named as a share rather than a size so the projectile can decide what to do with it, and so the
     * shape of the curve lives in one place: a lone tube is barely worth avoiding, and a full caldera
     * floor is the thing you build shelter against.
     */
    fun forceOf(mass: Set<BlockPos>): Double =
        (mass.size.toDouble() / MOST_IN_A_MASS).coerceAtMost(EVERYTHING)

    private const val MOST_IN_A_MASS = 64
    private const val POURED_PER_TICK = 32

    /** Far enough to flood a caldera floor, short enough that a breached rim does not drain into the world. */
    private const val REACH_FROM_THE_MASS = 24.0

    private const val ONE = 1
    private const val EVERYTHING = 1.0
}

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
     * Lay some of the lava this mass owes the hollow above it, up to [POURED_PER_TICK], and say how much
     * went in.
     *
     * **A level at a time, and only a level that is held in.** A pool is filled from the bottom up, one
     * whole layer per pass, and a layer that reaches somewhere the lava would run *off* is abandoned
     * entirely rather than partly laid — which is what stops a vent on open ground hanging a disc of lava
     * in the air and cascading it down the hillside (Jonah, walked). What is left is a pool that rises to
     * the brim of whatever holds it and no further.
     *
     * The consequence worth knowing: **a caldera whose rim is breached below the brim fills to the breach
     * and stops**, and one breached at the floor never fills at all. That makes cutting the rim a second,
     * permanent way to silence a volcano's lake, beside plugging the vents.
     *
     * Lava is placed as **source blocks in a computed volume, never allowed to flow**. Flowing is the
     * expensive half, and a computed pool is idempotent: running this again over a full caldera walks the
     * same space, places nothing, and answers zero — which is what tells the caller to stop.
     */
    fun pour(level: ServerLevel, at: BlockPos): Int {
        if (plugged(level, at)) return NOTHING_POURED
        val mass = massAround(level, at)
        val brim = mass.maxOf { it.y } + depthOf(mass)
        var poured = 0
        var resting: Collection<BlockPos> = mass
        var height = mass.maxOf { it.y } + ONE
        while (height <= brim) {
            val layer = heldLayer(level, height, resting) ?: break
            if (layer.isEmpty()) break
            for (position in layer) {
                if (poured >= POURED_PER_TICK) return poured
                if (level.getBlockState(position).`is`(Blocks.LAVA)) continue
                level.setBlockAndUpdate(position, Blocks.LAVA.defaultBlockState())
                poured++
            }
            resting = layer
            height++
        }
        return poured
    }

    /**
     * The whole of one level of pool standing on [resting], or **null where it would run off**.
     *
     * The walk spreads sideways through anything a fluid could occupy and stops at anything that blocks
     * motion, which is a wall holding the pool in. Reaching open space with *nothing under it* is the
     * other outcome, and it is not a smaller pool — it is a leak, and the level cannot stand at all.
     *
     * Bounded by [MOST_IN_A_LAYER] as well: a level too big to walk is one this cannot show is held in, and
     * refusing it is both the cheap answer and the right one.
     */
    private fun heldLayer(level: ServerLevel, height: Int, resting: Collection<BlockPos>): Set<BlockPos>? {
        val found = LinkedHashSet<BlockPos>()
        val queue = ArrayDeque<BlockPos>()
        for (under in resting) {
            val seed = under.above()
            if (seed.y != height) continue
            if (level.getBlockState(seed).blocksMotion()) continue
            queue += seed
        }
        while (queue.isNotEmpty()) {
            val here = queue.removeFirst()
            if (!found.add(here)) continue
            if (found.size > MOST_IN_A_LAYER) return null
            for (way in Direction.Plane.HORIZONTAL) {
                val neighbour = here.relative(way)
                if (level.getBlockState(neighbour).blocksMotion()) continue
                if (!heldUp(level, neighbour)) return null
                queue += neighbour
            }
        }
        return found
    }

    /** Whether there is anything under [at] for lava to rest on — the rock it sits in, or its own pool. */
    private fun heldUp(level: BlockGetter, at: BlockPos): Boolean {
        val under = level.getBlockState(at.below())
        return under.blocksMotion() || under.`is`(Blocks.LAVA)
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

    /**
     * How wide a single level of pool may be before this gives up on showing it is held in.
     *
     * Comfortably past the widest caldera floor, and it is the only size bound left — a pool no longer has
     * a radius, it has a container.
     */
    private const val MOST_IN_A_LAYER = 2048

    private const val ONE = 1
    private const val NOTHING_POURED = 0
    private const val NOTHING = 0.0
    private const val EVERYTHING = 1.0
}

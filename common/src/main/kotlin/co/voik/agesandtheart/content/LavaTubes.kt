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
     * **Every block of it rests on something, and that is the whole rule.** The first version spread
     * through anything a fluid could occupy, which on open ground is *air* — so a vent on a shelf hung a
     * disc of lava out over the edge and cascaded it down the hillside. The second refused a whole level
     * the moment any part of it would run off, which is the same mistake from the other side: one
     * block of drop anywhere near the vent and a caldera never filled at all (Jonah, walked, twice).
     *
     * What is here instead is local and has no opinion about containment. Lava stands where something
     * holds it up; where nothing does, it **falls**, and is followed down until it finds a floor. A dip in
     * the crater floor is filled by that fall, and a drop off the rim is told apart from a dip by nothing
     * cleverer than how far the fall goes: [MOST_DESCENT] blocks of it and this stops following, so a
     * cascade lays nothing while a hollow gets everything.
     *
     * Lava is placed as **source blocks in a computed volume, never allowed to flow**. Flowing is the
     * expensive half, and a computed pool is idempotent: running this again over a full caldera walks the
     * same space, places nothing, and answers zero — which is what tells the caller to stop.
     */
    fun pour(level: ServerLevel, at: BlockPos): Int {
        if (plugged(level, at)) return NOTHING_POURED
        val mass = massAround(level, at)
        val top = mass.maxOf { it.y }
        val brim = top + depthOf(mass)
        val lowestItMayReach = top - MOST_DESCENT
        val middleX = mass.sumOf { it.x } / mass.size
        val middleZ = mass.sumOf { it.z } / mass.size
        val reach = reachOf(mass)

        val seen = HashSet<BlockPos>(mass)
        val queue = ArrayDeque<BlockPos>()
        mass.forEach { tube -> queue += tube.above() }
        var poured = 0
        var walked = 0
        while (queue.isNotEmpty() && poured < POURED_PER_TICK && walked < MOST_IN_A_POOL) {
            val here = queue.removeFirst()
            if (!seen.add(here)) continue
            walked++
            if (here.y > brim || here.y < lowestItMayReach) continue
            if (!withinReach(here, middleX, middleZ, reach)) continue
            val standing = level.getBlockState(here)
            if (standing.blocksMotion()) continue
            // **Nothing under it: the lava falls rather than hangs.** Following it down is what fills a
            // dip in the crater floor, and running out of descent before finding a floor is what tells a
            // cascade off the rim from a hollow worth filling — one gets no lava, the other gets it all.
            if (!heldUp(level, here)) {
                queue += here.below()
                continue
            }
            // Walked past either way: the far side of a lip is where a dip's floor is found, and filling
            // that floor is what stops the lip being one on the next pass.
            Direction.Plane.HORIZONTAL.forEach { way -> queue += here.relative(way) }
            if (atTheLipOfADrop(level, here)) continue
            if (!standing.`is`(Blocks.LAVA)) {
                level.setBlockAndUpdate(here, Blocks.LAVA.defaultBlockState())
                poured++
            }
            queue += here.above()
        }
        return poured
    }

    /**
     * Whether anything beside [at] is open with nothing under it — the lip of a drop.
     *
     * A pool should not sit flush against a precipice, so it keeps a block back from one. It costs a
     * caldera nothing — a crater wall rises, so it is solid rather than a drop, and the only places a lake
     * gives up a block are the breaches it would have drained through anyway.
     *
     * **It does not stop lava pouring over the edge, and it was measured not to.** A source always spreads
     * flowing lava to its neighbours, so a margin is simply crossed: on a vent standing alone this took the
     * pool from forty-nine sources to twenty-five and left five hundred blocks of vanilla's own flowing
     * lava falling off the same edge. Worth knowing before reaching for a wider margin — the containable
     * distance is lava's whole flow reach, which is four blocks and eight in an ultrawarm Age.
     */
    private fun atTheLipOfADrop(level: BlockGetter, at: BlockPos): Boolean =
        Direction.Plane.HORIZONTAL.any { way ->
            val beside = at.relative(way)
            !level.getBlockState(beside).blocksMotion() && !heldUp(level, beside)
        }

    /** Whether the pool may stand this far from the vent at all — the size of a lake, not its shape. */
    private fun withinReach(position: BlockPos, middleX: Int, middleZ: Int, reach: Double): Boolean {
        val spreadX = (position.x - middleX).toDouble()
        val spreadZ = (position.z - middleZ).toDouble()
        return spreadX * spreadX + spreadZ * spreadZ <= reach * reach
    }

    /**
     * How wide a lake this mass is owed — **the size of the vent, squared into the size of the pool**.
     *
     * A flat reach gave a five-block seam exposed on a hillside the same lake as a volcano's crater, which
     * is what a walk saw as a huge disc of lava on the side of a hill. A vent that only ever seeps should
     * make a puddle; a caldera's should make a lake.
     *
     * **Squared, and saturating at [ENOUGH_TO_THROW], for two reasons.** Squaring is what makes the small
     * end small — a seam of five gets a couple of blocks where a straight share would still give it eight
     * — and saturating at the throwing threshold means every mass big enough to throw already has the full
     * reach, so no caldera vent's lake changes by a block.
     */
    private fun reachOf(mass: Set<BlockPos>): Double {
        val share = (mass.size.toDouble() / ENOUGH_TO_THROW).coerceAtMost(EVERYTHING)
        return REACH_FROM_THE_MASS * share * share
    }

    /**
     * Whether there is anything under [at] for lava to rest on — the rock it sits in, or its own pool.
     *
     * **A *source* of lava, never a running one, and the distinction is the whole of a bug this had.**
     * Placing a source lets vanilla spread flowing lava off the edge and down the drop, and flowing lava is
     * the same block: counting it as support made a falling sheet look like a floor, so the next pass laid
     * fresh sources against the fall, which ran further still. A vent on a hillside grew a disc of lava
     * hanging in the air a fifteenth the size of what it should have laid, and it grew every couple of
     * ticks because the two mechanisms fed each other.
     */
    private fun heldUp(level: BlockGetter, at: BlockPos): Boolean {
        val under = level.getBlockState(at.below())
        return under.blocksMotion() || (under.`is`(Blocks.LAVA) && under.fluidState.isSource)
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
     * How far a fall is followed before it counts as running away rather than settling.
     *
     * This is the only thing separating a dip in a crater floor from a drop off the crater's rim, and it
     * wants to stay small: generous enough for the rumple the surface noise leaves, mean enough that a
     * mountainside is never mistaken for a hollow.
     */
    private const val MOST_DESCENT = 4

    /** What a vent big enough to throw is owed. Smaller ones get a share of it — see [reachOf]. */
    private const val REACH_FROM_THE_MASS = 24.0

    /** A bound on the walk rather than on the lava: what one pass may look at before yielding the tick. */
    private const val MOST_IN_A_POOL = 4096

    private const val ONE = 1
    private const val NOTHING_POURED = 0
    private const val NOTHING = 0.0
    private const val EVERYTHING = 1.0
}

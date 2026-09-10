package co.voik.agesandtheart.content

import co.voik.agesandtheart.age.phenomena.VolcanicBomb
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.material.FlowingFluid
import net.minecraft.world.level.material.Fluids

/**
 * What a lava tube does, and what a mass of them is worth (design §7.1.2).
 *
 * **Two errands, both on random ticks, and separating them is what keeps the material honest.** Welling
 * is what makes a tube worth having and is bounded by [REACH]; throwing is what makes one worth fearing
 * and is bounded by nothing, because a bomb is slow, random and trying to kill you. A tube that is
 * covered does neither — so a lava supply you can live beside is one you have to leave open and erupting.
 *
 * **Where lava may stand is vanilla's fluid to know, and it is the only thing that knows.** Every attempt
 * to work it out here failed the same way: a computed pool has to decide what holds lava up, and the
 * answer is a property of the whole basin rather than of any block in it, so a rule that fills a crater
 * also hangs a disc off a hillside and a rule that refuses the hillside never fills the crater. So this
 * does not compute a pool at all. It puts one block where it can and then **hardens what vanilla's own
 * flow has already found**, which by construction is only ever somewhere lava could go.
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

    /**
     * Lay one block of lava, if this tube can reach anywhere to lay it.
     *
     * First the space directly overhead, which is what floods a tunnel somebody has just broken into.
     * Failing that — and a tube standing under its own pool always fails it — the nearest running lava
     * the pool reaches is **turned to a source**, so the pool creeps out one block at a time along
     * whatever path vanilla's flow actually found. That is a slow ratchet on a fast spread: the flowing
     * lava arrives in seconds and is what hurts, and this is what makes it permanent.
     *
     * Answers whether anything went in, which is what tells [LavaTubeBlock] whether to come back: a tube
     * with nowhere left to put lava stops costing anything at all until something changes near it.
     */
    fun well(level: ServerLevel, at: BlockPos): Boolean {
        if (plugged(level, at)) return NOTHING_TO_DO
        if (floodedOverhead(level, at)) return SOMETHING_WENT_IN
        val running = nearestRunning(level, at) ?: return NOTHING_TO_DO
        level.setBlockAndUpdate(running, LAVA)
        return SOMETHING_WENT_IN
    }

    /** Whether a block of lava went in directly overhead. */
    private fun floodedOverhead(level: ServerLevel, at: BlockPos): Boolean {
        val over = at.above()
        if (!level.getBlockState(over).isAir) return false
        level.setBlockAndUpdate(over, LAVA)
        return true
    }

    /**
     * The closest running lava this tube's own pool leads to, within [REACH].
     *
     * **Walked outward through source lava rather than scanned in a box**, which buys three things at
     * once: the nearest is found first by construction, the search stops the moment it reaches a frontier
     * instead of reading a thousand columns, and what it finds is connected to *this* tube — so a vent
     * cannot annex the edge of a lava lake that happens to lie next to it.
     *
     * **Falling lava is never taken**, and that single condition is what stands in for every containment
     * rule this used to need. Vanilla only ever puts flowing lava where lava can go, so hardening one is
     * safe everywhere — except in mid-air, where hardening the column of a fall would build a pillar of
     * lava hanging off a ledge. Skipping it leaves the fall a fall, and the pool re-forms at its foot.
     */
    private fun nearestRunning(level: ServerLevel, at: BlockPos): BlockPos? {
        val seen = HashSet<BlockPos>()
        seen += at
        val queue = ArrayDeque(listOf(at))
        var walked = 0
        while (queue.isNotEmpty() && walked < MOST_LOOKED_AT) {
            val here = queue.removeFirst()
            walked++
            for (way in Direction.entries) {
                val next = here.relative(way)
                if (!seen.add(next)) continue
                if (next.distSqr(at) > REACH * REACH) continue
                val fluid = level.getFluidState(next)
                when (fluid.type) {
                    Fluids.LAVA -> queue += next
                    Fluids.FLOWING_LAVA -> if (!fluid.getValueOrElse(FlowingFluid.FALLING, false)) return next
                    else -> Unit
                }
            }
        }
        return null
    }

    /**
     * Throw something, if this visit is one of the ones that throws.
     *
     * **A probability rather than a threshold** (Jonah, 2026-09-09). A hard minimum mass made the material
     * a cliff — sixteen blocks was the difference between a warm curiosity and a barrage — where what it
     * should be is a curve. A tube on its own throws about once every two minutes, which is a genuine if
     * rare threat in a mine; a crowded one throws on nearly every visit. Danger then scales twice over,
     * once because a mass gets more visits and once because each visit is likelier to fire, and the
     * ceiling is the random tick itself: no block can throw more often than it is visited.
     */
    fun erupt(level: ServerLevel, at: BlockPos, random: RandomSource) {
        if (plugged(level, at)) return
        if (random.nextDouble() >= eagerness(level, at)) return
        val mass = massAround(level, at)
        VolcanicBomb.thrownFrom(level, mouthOver(level, mass.maxByOrNull { it.y } ?: at), forceOf(mass))
    }

    /**
     * How likely this tube is to throw on a visit — **read off its immediate neighbourhood, not its mass**.
     *
     * A locality claim is what "a group of them buff each other" actually says, and it is a twenty-six
     * block read where walking the connected mass is hundreds. The mass is still what sets the *force*,
     * but that is only paid on the rare visit that fires.
     */
    private fun eagerness(level: BlockGetter, at: BlockPos): Double {
        var crowd = 0
        for (offsetX in -ONE..ONE) {
            for (offsetY in -ONE..ONE) {
                for (offsetZ in -ONE..ONE) {
                    if (offsetX == 0 && offsetY == 0 && offsetZ == 0) continue
                    val beside = at.offset(offsetX, offsetY, offsetZ)
                    if (level.getBlockState(beside).`is`(AgeContent.LAVA_TUBE_BLOCK)) crowd++
                }
            }
        }
        return eagernessAmong(crowd)
    }

    /**
     * The same curve without a world to read it from, so the cadence it produces can be checked rather
     * than walked — two minutes between a lone tube's shots is not a thing anybody can time in game.
     */
    fun eagernessAmong(crowd: Int): Double {
        val share = crowd.coerceIn(NONE_BESIDE, MOST_NEIGHBOURS).toDouble() / MOST_NEIGHBOURS
        return ALONE + (CROWDED - ALONE) * share
    }

    /** How often vanilla visits a given block, at the default random tick rate — the ceiling on all of this. */
    const val TICKS_BETWEEN_VISITS = 4096.0 / 3.0

    /**
     * How hard a volcano with this mass throws, as a share of the whole.
     *
     * At the bottom a bomb falls back into the crater that threw it, which is what a single tube in a
     * tunnel should manage and no more; at [MOST_IN_A_MASS] it clears seven or eight chunks.
     */
    fun forceOf(mass: Set<BlockPos>): Double =
        (mass.size.toDouble() / MOST_IN_A_MASS).coerceIn(NOTHING, EVERYTHING)

    /**
     * The open air over a vent, above whatever lava is standing on it.
     *
     * **A vent under its own lake throws from the surface of it.** Spawning at the tube put every bomb
     * inside lava, where it was quenched by the rule meant to stop the Age flooding — the volcano threw
     * nothing and looked broken.
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

    private val LAVA = Blocks.LAVA.defaultBlockState()

    private const val MOST_IN_A_MASS = 64

    /** A pool is capped at this above the vent, so there is never much more lava than this to climb. */
    private const val MOST_LAVA_OVERHEAD = 12

    /**
     * How far from itself a tube may harden lava — **the only bound on how much lava exists**, now that
     * nothing computes a pool.
     *
     * A lone tube can eventually make a pond this wide and no wider; a mass makes the union of one of
     * these per block, which is what lets a caldera's vent hold a lake and a seam hold a puddle with no
     * rule that mentions either.
     */
    private const val REACH = 8

    /** A safety net over a search the reach already bounds — the ball itself is smaller than this. */
    private const val MOST_LOOKED_AT = 2500

    private const val NOTHING_TO_DO = false
    private const val SOMETHING_WENT_IN = true

    /** Every block touching this one, corners included. */
    private const val MOST_NEIGHBOURS = 26

    private const val NONE_BESIDE = 0

    /**
     * How likely a visit is to throw, alone and crowded.
     *
     * A block is visited about once every 1,365 ticks at the default random tick rate, so the low end is
     * set to put a lone tube's shot a little over two minutes apart. The high end is every visit, which is
     * the most a block can do.
     */
    private const val ALONE = 0.57
    private const val CROWDED = 1.0

    private const val ONE = 1
    private const val NOTHING = 0.0
    private const val EVERYTHING = 1.0
}

package co.voik.agesandtheart.content

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
     * What a visit found — and the distinction between the last two is the whole of why a tube placed on
     * flat ground used to stall.
     *
     * [NOT_YET] and [NOWHERE_LEFT] both put no lava down, and booking the next tick on that alone stopped
     * the chain dead on its **second** pass: the first laid a source overhead, and the second found the
     * space above taken and no *running* lava to harden, because vanilla spreads every thirty ticks against
     * [WELL_DELAY]'s ten. Nothing was booked and only a random tick ever restarted it. Raising the delay
     * past thirty would mask that and is the worse answer, since it slows every tube to fix the first two
     * ticks of one.
     */
    enum class Welling(val comeBackIn: Int) {
        /** A block went in, so there is certainly more to do, and the pool is gaining on the flow. */
        PLACED(WELL_DELAY),

        /**
         * Nothing went in, but somewhere within reach could still take lava once vanilla's flow arrives.
         *
         * **Waiting happens at vanilla's pace, not ours** (Jonah, 2026-09-11): the thing being waited for
         * spreads every thirty ticks, so coming back every ten is two walks of the pool wasted out of
         * three. Nobody notices the wait — the flow is what they are waiting for either way.
         */
        NOT_YET(WAITING_ON_THE_FLOW),

        /** Plugged, or the reach is full: nothing will change here until something changes near it. */
        NOWHERE_LEFT(NEVER),
        ;

        /** Whether [LavaTubeBlock] should book another visit. */
        val worthComingBack: Boolean get() = this != NOWHERE_LEFT
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
     * Answers what [LavaTubeBlock] needs to decide whether to come back — see [Welling]. A tube with
     * nowhere left to put lava stops costing anything at all until something changes near it.
     */
    fun well(level: ServerLevel, at: BlockPos): Welling {
        if (plugged(level, at)) return Welling.NOWHERE_LEFT
        if (floodedOverhead(level, at)) return Welling.PLACED
        val reached = reachable(level, at)
        reached.running?.let {
            level.setBlockAndUpdate(it, LAVA)
            return Welling.PLACED
        }
        return if (reached.roomToGrow) Welling.NOT_YET else Welling.NOWHERE_LEFT
    }

    /** Whether a block of lava went in directly overhead. */
    private fun floodedOverhead(level: ServerLevel, at: BlockPos): Boolean {
        val over = at.above()
        if (!level.getBlockState(over).isAir) return false
        level.setBlockAndUpdate(over, LAVA)
        return true
    }

    /** What one walk of the pool found — see [Welling], which is decided from exactly these two. */
    private class Reached(val running: BlockPos?, val roomToGrow: Boolean)

    /**
     * The closest running lava this tube's own pool leads to within [REACH], **and whether there is
     * anywhere left for lava to arrive at all**.
     *
     * The second answer is what separates *waiting on vanilla's flow* from *finished*, and it costs
     * nothing extra: the walk is already visiting every neighbour of the pool, so an air block that is
     * within reach and standing on rock is a place vanilla will eventually flow into and this tube will
     * eventually harden. None anywhere means the reach is full and the chain may stop.
     *
     * **Air is the signal rather than running lava**, which is the trap: on the tick after a tube lays its
     * first source there is no running lava anywhere yet, so a test for it reads a tube that has barely
     * started as one that has finished.
     *
     * **Walked outward through source lava rather than scanned in a box**, which buys three things at
     * once: the nearest is found first by construction, the search stops the moment it reaches a frontier
     * instead of reading a thousand columns, and what it finds is connected to *this* tube — so a vent
     * cannot annex the edge of a lava lake that happens to lie next to it.
     *
     * **Neither falling lava nor unsupported lava is taken, and it took both — CORRECTED 2026-09-10
     * (Jonah, walked).** Refusing the `FALLING` flag alone still built floating discs, and the reason is
     * one tick of vanilla's own spreading: lava that runs out over a lip exists at that lip as *flowing,
     * not falling*, for as long as it takes the fall beneath it to appear. Harden one of those and the
     * result is a **source** hanging in mid-air — and a source never drains, so it pours for ever and the
     * next visit hardens its neighbour, which is the disc.
     *
     * **Spreading is sideways only, and that falls out of the support rule rather than needing a cap of
     * its own.** A height limit above the vent was tried and is gone: nothing standing on lava is ever
     * converted, so a pool cannot build a second storey and there is no height to limit. What a tube does
     * is lay one layer along whatever floor its lava found.
     *
     * So what is taken has to be standing on rock. [standingOnRock] is the other half of it:
     * vanilla only ever puts flowing lava where lava can go, so anywhere it is *supported* is somewhere a
     * pool may sit, and everywhere else is left a fall with its pool re-forming at the foot.
     */
    private fun reachable(level: ServerLevel, at: BlockPos): Reached {
        val seen = HashSet<BlockPos>()
        seen += at
        val queue = ArrayDeque(listOf(at))
        var walked = 0
        var roomToGrow = false
        while (queue.isNotEmpty() && walked < MOST_LOOKED_AT) {
            val here = queue.removeFirst()
            walked++
            for (way in Direction.entries) {
                val next = here.relative(way)
                if (!seen.add(next)) continue
                if (next.distSqr(at) > REACH * REACH) continue
                val fluid = level.getFluidState(next)
                when (fluid.type) {
                    // Walked THROUGH, both of them: a frontier can lie past a tongue of running lava, and
                    // stopping at the first one it met left a pool unable to reach round its own spill.
                    Fluids.LAVA -> queue += next
                    Fluids.FLOWING_LAVA -> {
                        if (!fluid.getValueOrElse(FlowingFluid.FALLING, false) && standingOnRock(level, next)) {
                            return Reached(running = next, roomToGrow = true)
                        }
                        queue += next
                    }
                    // Somewhere the flow can still arrive, which is what says "come back" rather than
                    // "finished". Not walked through — this is the pool's edge, not part of it.
                    else -> if (level.getBlockState(next).isAir && standingOnRock(level, next)) roomToGrow = true
                }
            }
        }
        return Reached(running = null, roomToGrow = roomToGrow)
    }

    /**
     * Whether lava at [at] is standing on **rock** — not on more lava, and not on nothing.
     *
     * **Lava is not support, and letting it be was the whole bug** (Jonah, walked 2026-09-10). A pool
     * standing on a pool sounds like what a pool is, but it is what lets a tube climb: convert the block
     * resting on the source, then the one resting on *that*, and a tube laid on flat ground fills upward
     * two and three blocks at a time. `blocksMotion` is the exact question — lava does not block motion,
     * so rock passes and lava does not, with nothing to say about it twice.
     */
    private fun standingOnRock(level: ServerLevel, at: BlockPos): Boolean =
        level.getBlockState(at.below()).blocksMotion()

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

    /**
     * How often a tube visits while it hardens, and how long it waits on a flow that has not arrived.
     *
     * The wait clears vanilla's own thirty-tick spread with a little to spare, so a visit that finds
     * nothing has something new to look at rather than re-walking the same pool.
     */
    private const val WELL_DELAY = 10
    private const val WAITING_ON_THE_FLOW = 40

    /** [Welling.NOWHERE_LEFT] books nothing, so its delay is never read. */
    private const val NEVER = 0


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

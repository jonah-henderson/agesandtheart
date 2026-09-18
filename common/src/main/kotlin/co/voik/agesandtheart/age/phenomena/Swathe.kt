package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.math.mix64
import co.voik.agesandtheart.math.unitDouble
import co.voik.agesandtheart.worldgen.field.fieldNoise
import co.voik.agesandtheart.worldgen.fissure.Crack
import com.mojang.serialization.Codec
import net.minecraft.core.BlockPos
import net.minecraft.util.RandomSource
import net.minecraft.util.StringRepresentable
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The swathe one cave-in takes out of the world — a mask [CaveIn] then eats inward from wherever the open
 * air can reach it.
 *
 * **A column is asked once and its courses many times**, which is why [howCentral] is separate from
 * [takes]: a sweep skips everything outside the plan without touching a block, and that early-out is what
 * keeps a large collapse affordable.
 *
 * **Nothing here is a template.** Two cave-ins of the same [CollapseShape] share the rule and nothing else:
 * dimensions, wander and roughening all come off the seed, so no two swathes of a kind are alike.
 */
interface Swathe {

    /**
     * How far out the plan may reach, and how far down from where the collapse began — bounds in the
     * proper sense, since the sweep loops over exactly these and a shape wanting ground past one simply
     * loses it.
     */
    val reachesOut: Int
    val reachesDown: Int

    /**
     * How far above where the collapse began the sweep keeps asking, which is **not** a bound: at and
     * above that point every shape takes its whole plan, so there is no height at which one stops wanting
     * ground. What this decides is how much of the slope a cave-in on a hillside takes with it.
     */
    val reachesUp: Int

    /**
     * How far into the plan a column stands: 1 down its middle, falling to 0 at its edge and below 0
     * outside it.
     *
     * **A column below nought is taken by no course at all**, which is the contract the sweep's early-out
     * rests on — a shape whose [takes] reached past its own plan would lose whatever the sweep skipped.
     */
    fun howCentral(awayX: Int, awayZ: Int): Double

    /** Whether the course at [awayY] takes a column standing [howCentral] far into the plan. */
    fun takes(awayX: Int, awayY: Int, awayZ: Int, howCentral: Double): Boolean

    companion object {
        /** One swathe of [shape], drawn off [seed] and spending [reach] however that shape spends it. */
        fun of(shape: CollapseShape, seed: Long, reach: Int): Swathe {
            val room = reach.coerceAtLeast(SMALLEST_REACH)
            return when (shape) {
                CollapseShape.FISSURE -> TornFissure(seed, room)
                CollapseShape.ELLIPSOID -> RoughBowl(seed, room)
                CollapseShape.BOLT -> ForkedBolt(seed, room)
                CollapseShape.RING -> Moat(seed, room)
                CollapseShape.CRESCENT -> CrescentScarp(seed, room)
                CollapseShape.SHAFT -> Shaft(seed, room)
                CollapseShape.SQUARE -> SquarePrism(seed, room)
            }
        }

        /** Small enough to be silly, large enough that no shape divides by a zero size. */
        private const val SMALLEST_REACH = 4
    }
}

/**
 * Which shape a cave-in takes — **weighted in `art/phenomenon/tectonics.json`**, so how often the ground
 * gives way as a bolt rather than as a bowl is content rather than code.
 */
enum class CollapseShape(val key: String) : StringRepresentable {
    /** A torn crack across the ground, narrowing to a trough down its own middle. */
    FISSURE("fissure"),

    /** A sinkhole: a rounded bowl with a roughened rim. */
    ELLIPSOID("ellipsoid"),

    /** A winding trunk that forks and forks again, leaving a network of narrow trenches. */
    BOLT("bolt"),

    /** An annulus, leaving the ground inside it standing as a pillar over a moat. */
    RING("ring"),

    /** A curved headscarp, deepest at its crown and tapering to points at its horns. */
    CRESCENT("crescent"),

    /** A narrow hole that goes a long way down, leaning as it descends. */
    SHAFT("shaft"),

    /** Sharp corners, vertical walls, a level floor — nothing about it looks like it happened. */
    SQUARE("square"),
    ;

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<CollapseShape> = StringRepresentable.fromEnum(CollapseShape::values)

        fun named(key: String): CollapseShape? = entries.firstOrNull { it.key == key }
    }
}

/**
 * A rim that wanders in and out of the curve it was drawn from, as a share of the local half-width.
 *
 * Off the field toolkit's own noise rather than a hash per block, and the difference is the whole point: a
 * hash speckles single blocks along an edge, where noise moves a stretch of it at a time, which is what
 * reads as rock rather than as a dithered line.
 */
private class WanderingRim(seed: Long, private val wanders: Double) {
    private val noise = fieldNoise(mix64(seed), OCTAVE, AMPLITUDES)

    fun at(awayX: Int, awayZ: Int): Double =
        noise.getValue(awayX * SCALE, 0.0, awayZ * SCALE) * wanders

    private companion object {
        const val OCTAVE = -3
        const val SCALE = 0.12
        val AMPLITUDES = listOf(1.0, 0.5)
    }
}

/**
 * How central a column has to be for the course at [awayY] to take it: nothing at the top, and tightening
 * with depth, so a swathe narrows to a trough down its own middle rather than dropping vertical walls onto
 * a level floor.
 *
 * [narrowsBy] is how sharply it closes — two is the fissure's own bowl, and a large power holds the walls
 * near vertical and rounds only the last course or two. Roughened per block, read off the seed and the
 * position, so a reloaded cave-in cuts the trough it was cutting before.
 */
private fun troughClaims(
    seed: Long,
    awayX: Int,
    awayY: Int,
    awayZ: Int,
    reachesDown: Int,
    narrowsBy: Double,
    roughEdge: Double,
): Double {
    if (awayY >= AT_THE_TOP) return NOTHING_REQUIRED
    val depth = -awayY.toDouble() / reachesDown
    val rough = (unitDouble(mix64(seed xor BlockPos.asLong(awayX, awayY, awayZ))) * TWICE - ONE) * roughEdge
    return (depth.pow(narrowsBy) + rough).coerceAtLeast(NOTHING_REQUIRED)
}

/** A number somewhere in the band, which is how every drawn dimension here is drawn. */
private fun RandomSource.between(least: Double, most: Double): Double = least + nextDouble() * (most - least)

/** How far above where it began a swathe reaches, so a cave-in on a hillside takes the slope over it. */
private fun climbsAbove(reach: Int): Int = (reach * CLIMBS).roundToInt().coerceAtLeast(ONE_COURSE)

/**
 * A torn crack across the ground — the original shape, and still the commonest.
 *
 * The plan is [Crack]'s, the same maths a star fissure and a collapse tear are cut with, **read at the
 * scale [reachesOut] asks for**: the crack is drawn at its own length and the sweep's offsets are shrunk
 * into that frame, so a larger fissure is a longer and proportionately wider one rather than the same
 * crack with a wider margin around it.
 */
private class TornFissure(private val seed: Long, override val reachesOut: Int) : Swathe {
    private val crack = Crack.of(seed)
    private val intoTheCrack = Crack.HALF_LENGTH / reachesOut

    override val reachesUp = climbsAbove(reachesOut)
    override val reachesDown = (reachesOut * CUTS_DOWN).roundToInt().coerceAtLeast(ONE_COURSE)

    override fun howCentral(awayX: Int, awayZ: Int): Double =
        crack.centralityAt(awayX * intoTheCrack, awayZ * intoTheCrack)

    override fun takes(awayX: Int, awayY: Int, awayZ: Int, howCentral: Double): Boolean =
        howCentral >= troughClaims(seed, awayX, awayY, awayZ, reachesDown, NARROWS_BY, ROUGH_EDGE)

    private companion object {
        const val CUTS_DOWN = 0.5
        const val NARROWS_BY = 2.0
        const val ROUGH_EDGE = 0.08
    }
}

/**
 * A sinkhole: a rounded bowl, wider than it is deep, with a rim that wanders off its own ellipse.
 *
 * **The least varied shape in the set, deliberately** — a bowl is a bowl, so what is drawn is how wide and
 * how deep rather than anything about its form. Its whole surface takes the rim's wander, because the
 * horizontal share [takes] works from is read back out of [howCentral] rather than recomputed.
 */
private class RoughBowl(seed: Long, reach: Int) : Swathe {
    private val random = XoroshiroRandomSource(seed)
    private val acrossX = reach * random.between(NARROWEST, WIDEST)
    private val acrossZ = reach * random.between(NARROWEST, WIDEST)
    private val downwards = reach * random.between(SHALLOWEST, DEEPEST)
    private val rim = WanderingRim(seed, RIM_WANDERS)

    override val reachesOut = ceil(maxOf(acrossX, acrossZ) * (ONE + RIM_WANDERS)).toInt() + SLACK
    override val reachesUp = climbsAbove(reach)
    override val reachesDown = ceil(downwards).toInt()

    override fun howCentral(awayX: Int, awayZ: Int): Double =
        ONE - hypot(awayX / acrossX, awayZ / acrossZ) + rim.at(awayX, awayZ)

    override fun takes(awayX: Int, awayY: Int, awayZ: Int, howCentral: Double): Boolean {
        val across = ONE - howCentral
        val down = awayY / downwards
        return across * across + down * down <= ONE
    }

    private companion object {
        const val NARROWEST = 0.35
        const val WIDEST = 0.55
        const val SHALLOWEST = 0.3
        const val DEEPEST = 0.45
        const val RIM_WANDERS = 0.16
    }
}

/** One straight run of a bolt, in the plan: where it goes and how wide it is along the way. */
private class Limb(
    val fromX: Double,
    val fromZ: Double,
    val toX: Double,
    val toZ: Double,
    val halfWidth: Double,
) {
    /** How central a point is to this limb alone: 1 on its line, 0 at its side, below 0 past it. */
    fun howCentralAt(awayX: Double, awayZ: Double): Double {
        val runX = toX - fromX
        val runZ = toZ - fromZ
        val lengthSquared = runX * runX + runZ * runZ
        val alongTheLimb =
            if (lengthSquared <= NOTHING_REQUIRED) NOTHING_REQUIRED
            else (((awayX - fromX) * runX + (awayZ - fromZ) * runZ) / lengthSquared).coerceIn(NOTHING_REQUIRED, ONE)
        val offCentre = hypot(awayX - (fromX + runX * alongTheLimb), awayZ - (fromZ + runZ * alongTheLimb))
        return ONE - offCentre / halfWidth
    }
}

/**
 * A bolt of collapsed ground: a trunk that kinks hard at every run and throws branches off it, each
 * branch thinner than what it left and free to kink and branch in its turn.
 *
 * **The trunk is backed up before it starts**, so the cave-in's own position falls somewhere along it
 * rather than at one end — a bolt that always began under the player would read as aimed.
 *
 * **Nothing tapers to nothing.** A limb narrower than [THINNEST] reads as a seam in the ground rather than
 * as a way into it, which is the same finding [Crack]'s width was corrected for.
 */
private class ForkedBolt(private val seed: Long, reach: Int) : Swathe {
    private val limbs = drawLimbs(XoroshiroRandomSource(seed), reach)
    private val rim = WanderingRim(seed, RIM_WANDERS)

    /** A limb's *centreline* is what the draw holds inside the reach, so its own width stands past it. */
    override val reachesOut = ceil(reach + limbs.maxOf { it.halfWidth } * (ONE + RIM_WANDERS)).toInt() + SLACK
    override val reachesUp = climbsAbove(reach)
    override val reachesDown = (reach * CUTS_DOWN).roundToInt().coerceAtLeast(ONE_COURSE)

    override fun howCentral(awayX: Int, awayZ: Int): Double {
        val nearestLimb = limbs.maxOf { it.howCentralAt(awayX.toDouble(), awayZ.toDouble()) }
        return nearestLimb + rim.at(awayX, awayZ)
    }

    override fun takes(awayX: Int, awayY: Int, awayZ: Int, howCentral: Double): Boolean =
        howCentral >= troughClaims(seed, awayX, awayY, awayZ, reachesDown, NARROWS_BY, ROUGH_EDGE)

    private companion object {
        const val CUTS_DOWN = 0.4
        const val NARROWS_BY = 2.0
        const val ROUGH_EDGE = 0.08
        const val RIM_WANDERS = 0.2

        const val FEWEST_RUNS = 5.0
        const val MOST_RUNS = 9.0
        const val SHORTEST_RUN = 0.8
        const val LONGEST_RUN = 1.5

        /** How far one run may turn off the last, in radians — a third of a turn, which is the snaking. */
        const val MOST_KINK = 1.05

        /** How far a fork leaves the limb it came off, in radians. */
        const val NARROWEST_FORK = 0.7
        const val WIDEST_FORK = 1.4

        const val FORKS = 0.5
        const val BRANCH_RUNS = 0.6
        const val BRANCH_THINS = 0.72
        const val TAPERS = 0.93
        const val THINNEST = 1.3
        const val WIDEST_TRUNK = 2.9
        const val NARROWEST_TRUNK = 2.1

        /** How far back along its own bearing the trunk begins, as a share of the reach. */
        const val STARTS_BACK = 0.45

        /** How many times a fork may fork again. */
        const val FORKS_DEEP = 2

        const val FULL_TURN = Math.PI * 2.0

        fun drawLimbs(random: RandomSource, reach: Int): List<Limb> {
            val limbs = mutableListOf<Limb>()
            val edge = reach.toDouble()

            fun snake(startX: Double, startZ: Double, bearing: Double, runs: Int, halfWidth: Double, mayFork: Int) {
                var atX = startX
                var atZ = startZ
                var heading = bearing
                var width = halfWidth
                repeat(runs) { run ->
                    heading += random.between(-MOST_KINK, MOST_KINK)
                    val step = edge * random.between(SHORTEST_RUN, LONGEST_RUN) / runs
                    val nextX = (atX + cos(heading) * step).coerceIn(-edge, edge)
                    val nextZ = (atZ + sin(heading) * step).coerceIn(-edge, edge)
                    limbs += Limb(atX, atZ, nextX, nextZ, width)
                    val forks = mayFork > 0 && run > 0 && random.nextDouble() < FORKS
                    if (forks) {
                        val turnsAway = if (random.nextBoolean()) ONE else -ONE
                        snake(
                            startX = nextX,
                            startZ = nextZ,
                            bearing = heading + turnsAway * random.between(NARROWEST_FORK, WIDEST_FORK),
                            runs = (runs * BRANCH_RUNS).roundToInt().coerceAtLeast(1),
                            halfWidth = (width * BRANCH_THINS).coerceAtLeast(THINNEST),
                            mayFork = mayFork - 1,
                        )
                    }
                    atX = nextX
                    atZ = nextZ
                    width = (width * TAPERS).coerceAtLeast(THINNEST)
                }
            }

            val bearing = random.nextDouble() * FULL_TURN
            val backOff = edge * STARTS_BACK
            snake(
                startX = -cos(bearing) * backOff,
                startZ = -sin(bearing) * backOff,
                bearing = bearing,
                runs = random.between(FEWEST_RUNS, MOST_RUNS).roundToInt(),
                halfWidth = random.between(NARROWEST_TRUNK, WIDEST_TRUNK),
                mayFork = FORKS_DEEP,
            )
            return limbs
        }
    }
}

/**
 * A moat: the ground gives way in a ring and leaves what stood inside it as a pillar.
 *
 * **Walls near vertical**, which is what the pillar needs — a trough that narrowed the ordinary amount
 * would undercut the ring into a bowl and take the pillar with it.
 */
private class Moat(private val seed: Long, reach: Int) : Swathe {
    private val random = XoroshiroRandomSource(seed)
    private val pillar = reach * random.between(SMALLEST_PILLAR, LARGEST_PILLAR)
    private val thickness = reach * random.between(THINNEST_RING, THICKEST_RING)
    private val middleRadius = pillar + thickness / TWICE
    private val rim = WanderingRim(seed, RIM_WANDERS)

    override val reachesOut = ceil((pillar + thickness) * (ONE + RIM_WANDERS)).toInt() + SLACK
    override val reachesUp = climbsAbove(reach)
    override val reachesDown = (reach * CUTS_DOWN).roundToInt().coerceAtLeast(ONE_COURSE)

    override fun howCentral(awayX: Int, awayZ: Int): Double {
        val outFromTheMiddle = hypot(awayX.toDouble(), awayZ.toDouble())
        val acrossTheRing = ONE - abs(outFromTheMiddle - middleRadius) / (thickness / TWICE)
        return acrossTheRing + rim.at(awayX, awayZ)
    }

    override fun takes(awayX: Int, awayY: Int, awayZ: Int, howCentral: Double): Boolean =
        howCentral >= troughClaims(seed, awayX, awayY, awayZ, reachesDown, NARROWS_BY, ROUGH_EDGE)

    private companion object {
        const val SMALLEST_PILLAR = 0.18
        const val LARGEST_PILLAR = 0.34
        const val THINNEST_RING = 0.22
        const val THICKEST_RING = 0.38
        const val CUTS_DOWN = 0.45
        const val NARROWS_BY = 6.0
        const val ROUGH_EDGE = 0.1
        const val RIM_WANDERS = 0.18
    }
}

/**
 * The scar a slumping hillside leaves: an arc, deepest at its crown and tapering to points at its horns.
 *
 * The depth follows from the plan and is not a second rule — the trough asks for more centrality the
 * further down it goes, and the crown is the most central part of an arc, so it is where the scarp bites.
 */
private class CrescentScarp(private val seed: Long, reach: Int) : Swathe {
    private val random = XoroshiroRandomSource(seed)
    private val radius = reach * random.between(TIGHTEST, WIDEST)
    private val thickness = reach * random.between(THINNEST, THICKEST)
    private val faces = random.nextDouble() * FULL_TURN
    private val halfSpan = random.between(NARROWEST_ARC, WIDEST_ARC)
    private val rim = WanderingRim(seed, RIM_WANDERS)

    override val reachesOut = ceil((radius + thickness) * (ONE + RIM_WANDERS)).toInt() + SLACK
    override val reachesUp = climbsAbove(reach)
    override val reachesDown = (reach * CUTS_DOWN).roundToInt().coerceAtLeast(ONE_COURSE)

    override fun howCentral(awayX: Int, awayZ: Int): Double {
        val outFromTheMiddle = hypot(awayX.toDouble(), awayZ.toDouble())
        val acrossTheArc = ONE - abs(outFromTheMiddle - radius) / (thickness / TWICE)
        val offTheCrown = abs(turnBetween(atan2(awayZ.toDouble(), awayX.toDouble()), faces))
        if (offTheCrown >= halfSpan) return OUTSIDE_IT
        val alongTheArc = ONE - offTheCrown / halfSpan
        return min(acrossTheArc, alongTheArc) + rim.at(awayX, awayZ)
    }

    override fun takes(awayX: Int, awayY: Int, awayZ: Int, howCentral: Double): Boolean =
        howCentral >= troughClaims(seed, awayX, awayY, awayZ, reachesDown, NARROWS_BY, ROUGH_EDGE)

    private companion object {
        const val TIGHTEST = 0.4
        const val WIDEST = 0.6
        const val THINNEST = 0.3
        const val THICKEST = 0.5
        const val CUTS_DOWN = 0.5
        const val NARROWS_BY = 1.6
        const val ROUGH_EDGE = 0.09
        const val RIM_WANDERS = 0.15
        const val FULL_TURN = Math.PI * 2.0

        /** How far round the crown an arc runs, in radians either side — a third to two thirds of a turn. */
        const val NARROWEST_ARC = 1.0
        const val WIDEST_ARC = 2.1

        /** The shorter way round from [one] to [other], in radians. */
        fun turnBetween(one: Double, other: Double): Double {
            val difference = (one - other) % FULL_TURN
            return when {
                difference > Math.PI -> difference - FULL_TURN
                difference < -Math.PI -> difference + FULL_TURN
                else -> difference
            }
        }
    }
}

/**
 * A narrow hole that goes a long way down, and **leans as it goes** — which is the whole of what keeps it
 * from reading as a bored well.
 *
 * The plan is the shaft's whole lean swept flat ([Limb]), so a sweep asks one question per column and the
 * course then decides where the middle actually is at that depth.
 */
private class Shaft(private val seed: Long, reach: Int) : Swathe {
    private val random = XoroshiroRandomSource(seed)
    private val radius = reach * random.between(NARROWEST, WIDEST)
    private val goesDown = (reach * random.between(SHALLOWEST, DEEPEST)).roundToInt().coerceAtLeast(ONE_COURSE)
    private val leansTowards = random.nextDouble() * FULL_TURN
    private val leansBy = random.between(STRAIGHTEST, MOST_LEAN)
    private val leansX = cos(leansTowards) * leansBy
    private val leansZ = sin(leansTowards) * leansBy
    private val wholeLean = Limb(NOTHING_REQUIRED, NOTHING_REQUIRED, leansX * goesDown, leansZ * goesDown, radius)
    private val rim = WanderingRim(seed, RIM_WANDERS)

    override val reachesOut = ceil((radius + abs(leansBy) * goesDown) * (ONE + RIM_WANDERS)).toInt() + SLACK
    override val reachesUp = climbsAbove(reach)
    override val reachesDown = goesDown

    override fun howCentral(awayX: Int, awayZ: Int): Double =
        wholeLean.howCentralAt(awayX.toDouble(), awayZ.toDouble()) + rim.at(awayX, awayZ)

    override fun takes(awayX: Int, awayY: Int, awayZ: Int, howCentral: Double): Boolean {
        if (awayY < -goesDown) return false
        // Above where it began the shaft has not started leaning, so those courses read the mouth.
        val atDepth = (-awayY).coerceAtLeast(NO_DEPTH)
        val offCentre = hypot(awayX - leansX * atDepth, awayZ - leansZ * atDepth)
        return ONE - offCentre / radius + rim.at(awayX, awayZ) >= NOTHING_REQUIRED
    }

    private companion object {
        const val NARROWEST = 0.12
        const val WIDEST = 0.22
        const val SHALLOWEST = 1.4
        const val DEEPEST = 2.4
        const val STRAIGHTEST = 0.05
        const val MOST_LEAN = 0.25
        const val RIM_WANDERS = 0.14
        const val NO_DEPTH = 0
        const val FULL_TURN = Math.PI * 2.0
    }
}

/**
 * Sharp corners, vertical walls, a level floor.
 *
 * **The one shape with no roughening of any kind**, which is the point of it: everything else here is
 * built to look like it happened, and this is built to look like something did it. What varies is only how
 * big it is and how far down it goes — a square that wobbled would not be a square.
 */
private class SquarePrism(seed: Long, reach: Int) : Swathe {
    private val random = XoroshiroRandomSource(seed)
    private val halfSide = (reach * random.between(SMALLEST, LARGEST)).roundToInt().coerceAtLeast(ONE_COURSE)
    private val goesDown = (reach * random.between(SHALLOWEST, DEEPEST)).roundToInt().coerceAtLeast(ONE_COURSE)

    override val reachesOut = halfSide
    override val reachesUp = climbsAbove(reach)
    override val reachesDown = goesDown

    override fun howCentral(awayX: Int, awayZ: Int): Double =
        if (abs(awayX) <= halfSide && abs(awayZ) <= halfSide) ONE else OUTSIDE_IT

    override fun takes(awayX: Int, awayY: Int, awayZ: Int, howCentral: Double): Boolean =
        howCentral >= NOTHING_REQUIRED && awayY >= -goesDown

    private companion object {
        const val SMALLEST = 0.22
        const val LARGEST = 0.38
        const val SHALLOWEST = 0.35
        const val DEEPEST = 0.6
    }
}

/** At and above where a cave-in began, a swathe asks nothing of a column beyond being in its plan. */
private const val AT_THE_TOP = 0
private const val NOTHING_REQUIRED = 0.0

/** What [Swathe.howCentral] says of a column no course will take. */
private const val OUTSIDE_IT = -1.0

private const val ONE = 1.0
private const val TWICE = 2.0
private const val ONE_COURSE = 1

/**
 * A block or two of room in every declared reach, because a [WanderingRim]'s noise is *normally*
 * distributed rather than bounded: it sits inside its amplitude nearly always and not quite always, and a
 * reach computed as though it were bounded would clip the one rim in a thousand that ran wide.
 */
private const val SLACK = 2

/** How far above where it began a swathe reaches, as a share of its reach — see [climbsAbove]. */
private const val CLIMBS = 0.5

package co.voik.agesandtheart.worldgen.field

import co.voik.agesandtheart.worldgen.VerticalWindow
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.util.StringRepresentable
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * One vast dead tree around its local origin, standing on [floorY] and broken off at [topY]: a trunk flared into
 * buttress fins and roots at its foot, hollow from the water to the sky, its wall holed by rot, dead branches
 * standing out near the top, and inside it shelves round the wall, one over another, with branches crossing the
 * hollow between them.
 *
 * The tree answers for one [part] of itself, so each material and each biome of the tree is a field laid exactly
 * where the shape put it: the whole of it, its bark, its heartwood, its living and dead shelves, the floor of its
 * hollow, or one of the three volumes its life is divided into — the swamp at the foot of the hollow, the forest
 * above it, and the deadwood: the crown, and the trunk outside over the shallows it stands in. Listed after the other
 * two, the deadwood's is everything of the tree near enough, the hollow included.
 */
data class HollowTree(
    val seed: Long,
    val floorY: Int,
    val topY: Int,
    val part: Part,
) : TerrainField {
    enum class Part(private val key: String) : StringRepresentable {
        WHOLE("whole"),
        BARK("bark"),
        HEARTWOOD("heartwood"),
        LIVING_SHELVES("living_shelves"),
        DEAD_SHELVES("dead_shelves"),
        HOLLOW_FLOOR("hollow_floor"),
        SWAMP("swamp"),
        FOREST("forest"),
        CROWN("crown"),
        ;

        override fun getSerializedName(): String = key

        companion object {
            val CODEC: Codec<Part> = StringRepresentable.fromEnum(Part::values)
        }
    }

    private enum class Cell { AIR, BARK, HEARTWOOD, LIVING_SHELF, DEAD_SHELF, FLOOR }

    private data class Hole(val angle: Double, val centreY: Double, val halfArc: Double, val halfHeight: Double)

    private data class Shelf(val y: Int, val thickness: Double, val firstPhase: Double, val secondPhase: Double)

    override val kind = FieldKind.HOLLOW_TREE

    private val span = (topY - floorY).coerceAtLeast(SHORTEST_SPAN)
    private val radius = span * RADIUS_OF_SPAN
    private val wall = max(THINNEST_WALL, radius * WALL_OF_RADIUS)
    private val bark = max(THINNEST_BARK, radius * BARK_OF_RADIUS)
    private val lowestY = floorY - FOOTING_DEPTH

    /** Where the swamp at the foot of the hollow gives way to the forest, and the forest to the deadwood. */
    val swampTopY = floorY + (span * SWAMP_SHARE).roundToInt()
    val forestTopY = floorY + (span * FOREST_SHARE).roundToInt()

    private val random = XoroshiroRandomSource(seed)
    private fun between(low: Double, high: Double) = low + random.nextDouble() * (high - low)
    private fun anyAngle() = random.nextDouble() * FULL_TURN

    private val leanX = between(-LEAN, LEAN) * radius
    private val leanZ = between(-LEAN, LEAN) * radius
    private val finCount = FEWEST_FINS + random.nextInt(FIN_CHOICES)
    private val finPhase = anyAngle()
    private val ridgeCount = max(FEWEST_RIDGES, (FULL_TURN * radius / RIDGE_SPACING).roundToInt())
    private val rimPhases = DoubleArray(RIM_HARMONICS.size) { anyAngle() }
    private val moundPhase = anyAngle()

    // Per height from [lowestY]: how the trunk narrows, flares and twists its ridges, worked out once.
    private val heights = topY - lowestY + 1
    private val taper = DoubleArray(heights) { index -> 1.0 - TAPER * shareUp(lowestY + index) }
    private val flare = DoubleArray(heights) { index -> FLARE * exp(-shareUp(lowestY + index) / FLARE_REACH) }
    private val finReach = DoubleArray(heights) { index -> FIN * exp(-shareUp(lowestY + index) / FIN_REACH) }
    private val twistCosine = DoubleArray(heights) { index -> cos(sin((lowestY + index) / TWIST_LENGTH) * TWIST) }
    private val twistSine = DoubleArray(heights) { index -> sin(sin((lowestY + index) / TWIST_LENGTH) * TWIST) }

    private val lowestJag: Double
    private val summitAngle: Double

    init {
        var least = Double.MAX_VALUE
        var summit = 0.0
        for (step in 0..<RIM_SAMPLES) {
            val angle = step * FULL_TURN / RIM_SAMPLES
            val jag = rawJag(angle)
            if (jag < least) {
                least = jag
                summit = angle
            }
        }
        lowestJag = least
        summitAngle = summit
    }

    private val holes: List<Hole> = buildList {
        add(Hole(anyAngle(), floorY.toDouble(), BASE_OPENING_ARC, BASE_OPENING_HEIGHT + span * BASE_OPENING_SHARE))
        repeat(FEWEST_HOLES + random.nextInt(HOLE_CHOICES)) {
            val centreY = between(swampTopY.toDouble(), topY - span * HOLE_CLEARANCE)
            add(Hole(anyAngle(), centreY, between(LEAST_HOLE_ARC, MOST_HOLE_ARC), radius * between(LEAST_HOLE_HEIGHT, MOST_HOLE_HEIGHT)))
        }
    }

    private val shelves: List<Shelf> = buildList {
        val spacing = (span * SHELF_SPACING).coerceIn(CLOSEST_SHELVES, FARTHEST_SHELVES)
        var shelfY = swampTopY + spacing * FIRST_SHELF
        while (shelfY < topY - spacing * LAST_SHELF_CLEARANCE) {
            val thickness = max(THINNEST_SHELF, span * SHELF_THICKNESS)
            add(Shelf(shelfY.roundToInt(), thickness, anyAngle(), anyAngle()))
            shelfY += spacing * between(1.0 - SHELF_SPACING_SWING, 1.0 + SHELF_SPACING_SWING)
        }
    }

    /** Branches across the hollow between one shelf and the next, heartwood like the wall they grow from. */
    private val crossings: List<Beam> = buildList {
        val levels = listOf(swampTopY) + shelves.map { it.y }
        for ((lower, upper) in levels.zipWithNext()) {
            val gap = upper - lower
            repeat(1 + random.nextInt(CROSSING_CHOICES)) {
                val from = anyAngle()
                val to = from + PI + between(-CROSSING_SKEW, CROSSING_SKEW)
                val fromY = lower + CROSSING_CLEARANCE + random.nextDouble() * gap * CROSSING_RISE
                val toY = upper - CROSSING_CLEARANCE * 2 - random.nextDouble() * gap * CROSSING_RISE
                add(acrossTheHollow(from, fromY, to, toY))
            }
        }
    }

    /** Roots running from the fins into the sea floor, and dead branches standing out from the top. */
    private val outgrowths: List<Beam> = buildList {
        val thickness = max(THINNEST_BRANCH, radius * BRANCH_OF_RADIUS)
        for (fin in 0..<finCount) {
            val angle = finPhase + fin * FULL_TURN / finCount
            val reach = radius * between(LEAST_ROOT_REACH, MOST_ROOT_REACH)
            add(
                Beam(
                    cos(angle) * radius, floorY + span * ROOT_HEIGHT, sin(angle) * radius,
                    cos(angle) * reach, floorY - ROOT_DEPTH, sin(angle) * reach,
                    thickness,
                ),
            )
        }
        repeat(FEWEST_STUBS + random.nextInt(STUB_CHOICES)) {
            val angle = anyAngle()
            val fromY = minOf(floorY + span * between(LOWEST_STUB, HIGHEST_STUB), rimAt(angle) - STUB_BELOW_RIM)
            val rise = between(LEAST_STUB_RISE, MOST_STUB_RISE)
            val length = radius * between(SHORTEST_STUB, LONGEST_STUB)
            val startX = cos(angle) * radius * STUB_ROOTED
            val startZ = sin(angle) * radius * STUB_ROOTED
            add(
                Beam(
                    startX, fromY, startZ,
                    startX + cos(angle) * cos(rise) * length, fromY + sin(rise) * length, startZ + sin(angle) * cos(rise) * length,
                    thickness,
                ),
            )
        }
    }

    override val horizontalReach: Double =
        maxOf(radius * (1.0 + FLARE + FIN) * (1.0 + RIDGE), outgrowths.maxOf { it.horizontalReach }, radius * CROWN_REACH) +
            hypot(leanX, leanZ) + REACH_SLACK

    /** Where to stand on the tree: on the highest point of its broken rim, relative to its origin. */
    fun summit(): Triple<Int, Int, Int> {
        val top = topY - lowestY
        val middleOfTheWall = radius * taper[top] * (1.0 + flare[top]) - wall / 2.0
        val x = leanX + cos(summitAngle) * middleOfTheWall
        val z = leanZ + sin(summitAngle) * middleOfTheWall
        return Triple(x.roundToInt(), rimAt(summitAngle).roundToInt() + 1, z.roundToInt())
    }

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        if (hypot(worldX.toDouble(), worldZ.toDouble()) > horizontalReach) return Spans.EMPTY
        // The angle round the trunk is read once per column, off the axis halfway up; the lean is slight.
        val angle = atan2(worldZ - leanZ * HALFWAY_LEAN, worldX - leanX * HALFWAY_LEAN)
        return when (part) {
            Part.WHOLE -> runsOf(worldX, worldZ, angle) { it != Cell.AIR }
            Part.BARK -> runsOf(worldX, worldZ, angle) { it == Cell.BARK }
            Part.HEARTWOOD -> runsOf(worldX, worldZ, angle) { it == Cell.HEARTWOOD }
            Part.LIVING_SHELVES -> runsOf(worldX, worldZ, angle) { it == Cell.LIVING_SHELF }
            Part.DEAD_SHELVES -> runsOf(worldX, worldZ, angle) { it == Cell.DEAD_SHELF }
            Part.HOLLOW_FLOOR -> runsOf(worldX, worldZ, angle) { it == Cell.FLOOR }
            Part.SWAMP -> hollowBetween(worldX, worldZ, lowestY, swampTopY)
            Part.FOREST -> hollowBetween(worldX, worldZ, swampTopY + 1, forestTopY)
            Part.CROWN ->
                if (hypot(worldX - leanX, worldZ - leanZ) > radius * CROWN_REACH) Spans.EMPTY
                else Spans.of(floorY + OVER_THE_SHALLOWS, VerticalWindow.HIGHEST_BLOCK_Y)
        }
    }

    private fun runsOf(worldX: Int, worldZ: Int, angle: Double, wanted: (Cell) -> Boolean): Spans {
        val finShape = cos(finCount * (angle - finPhase)).coerceAtLeast(0.0).let { it * it * it * it * it * it }
        val ridgeCosine = cos(ridgeCount * angle)
        val ridgeSine = sin(ridgeCount * angle)
        val rim = rimAt(angle)
        val ranges = mutableListOf<IntRange>()
        var runStart = Int.MIN_VALUE
        for (y in lowestY..topY) {
            val index = y - lowestY
            val share = shareUp(y)
            val distance = hypot(worldX - leanX * share * share, worldZ - leanZ * share * share)
            val ridge = RIDGE * (ridgeCosine * twistCosine[index] - ridgeSine * twistSine[index])
            val outer = radius * taper[index] * (1.0 + flare[index] + finReach[index] * finShape) * (1.0 + ridge)
            val inner = radius * taper[index] * (1.0 + flare[index] * INNER_FLARE) - wall
            val cell = when {
                distance > outer -> Cell.AIR
                distance >= inner -> when {
                    y > rim || isHoled(angle, y) -> Cell.AIR
                    distance >= outer - bark -> Cell.BARK
                    else -> Cell.HEARTWOOD
                }
                y <= floorAt(angle, distance, inner) -> Cell.FLOOR
                else -> shelfAt(angle, distance, inner, y, rim)
            }
            val counts = wanted(cell)
            if (counts && runStart == Int.MIN_VALUE) runStart = y
            if (!counts && runStart != Int.MIN_VALUE) {
                ranges += runStart..<y
                runStart = Int.MIN_VALUE
            }
        }
        if (runStart != Int.MIN_VALUE) ranges += runStart..topY
        val shaped = Spans.ofAscending(ranges)
        val beams = when {
            wanted(Cell.BARK) && wanted(Cell.HEARTWOOD) -> outgrowths + crossings
            wanted(Cell.BARK) -> outgrowths
            wanted(Cell.HEARTWOOD) -> crossings
            else -> return shaped
        }
        return beams.fold(shaped) { solid, beam -> solid.union(beam.columnSpans(worldX, worldZ)) }
    }

    /** The open hollow between two heights, with the inner face of its wall, as a volume a biome can fill. */
    private fun hollowBetween(worldX: Int, worldZ: Int, lowY: Int, highY: Int): Spans {
        val ranges = mutableListOf<IntRange>()
        var runStart = Int.MIN_VALUE
        for (y in lowY..highY) {
            val index = y - lowestY
            val share = shareUp(y)
            val distance = hypot(worldX - leanX * share * share, worldZ - leanZ * share * share)
            val inner = radius * taper[index] * (1.0 + flare[index] * INNER_FLARE) - wall
            val inside = distance < inner + INNER_FACE
            if (inside && runStart == Int.MIN_VALUE) runStart = y
            if (!inside && runStart != Int.MIN_VALUE) {
                ranges += runStart..<y
                runStart = Int.MIN_VALUE
            }
        }
        if (runStart != Int.MIN_VALUE) ranges += runStart..highY
        return Spans.ofAscending(ranges)
    }

    private fun shelfAt(angle: Double, distance: Double, inner: Double, y: Int, rim: Double): Cell {
        if (y > rim - SHELF_BELOW_RIM) return Cell.AIR
        for (shelf in shelves) {
            if (y > shelf.y || y < shelf.y - shelf.thickness * (1.0 + BRACKET)) continue
            val reach = 0.5 + 0.5 * (SHELF_FIRST * cos(2.0 * angle + shelf.firstPhase) + SHELF_SECOND * cos(3.0 * angle + shelf.secondPhase))
            if (reach < SHELF_GAP) continue
            val width = inner * (SHELF_LEAST + SHELF_MORE * reach)
            val fromTheWall = inner - distance
            if (fromTheWall > width) continue
            // Thicker where it meets the wall, as a bracket fungus is.
            val thickness = shelf.thickness * (1.0 + BRACKET * (1.0 - fromTheWall / width))
            if (y >= shelf.y - thickness) return if (shelf.y <= forestTopY) Cell.LIVING_SHELF else Cell.DEAD_SHELF
        }
        return Cell.AIR
    }

    /** The top of the hollow's own floor: low mounds of it standing out of the water, and a bank against the wall. */
    private fun floorAt(angle: Double, distance: Double, inner: Double): Int {
        val mound = (MOUND_FIRST * cos(3.0 * angle + moundPhase) + MOUND_SECOND * cos(distance / max(inner, 1.0) * MOUND_RINGS))
            .coerceAtLeast(0.0)
        val bank = (BANK_WIDTH - (inner - distance)).coerceAtLeast(0.0)
        return floorY + (mound * MOUND_HEIGHT + bank).roundToInt()
    }

    private fun isHoled(angle: Double, y: Int): Boolean = holes.any { hole ->
        val acrossY = (y - hole.centreY) / hole.halfHeight
        if (acrossY * acrossY >= 1.0) return@any false
        val apart = Math.IEEEremainder(angle - hole.angle, FULL_TURN) / hole.halfArc
        acrossY * acrossY + apart * apart < 1.0
    }

    private fun acrossTheHollow(fromAngle: Double, fromY: Double, toAngle: Double, toY: Double): Beam {
        fun innerAt(y: Double): Double {
            val index = (y.roundToInt() - lowestY).coerceIn(0, heights - 1)
            return radius * taper[index] * (1.0 + flare[index] * INNER_FLARE) - wall * CROSSING_ROOTED
        }
        val thickness = max(THINNEST_CROSSING, radius * CROSSING_OF_RADIUS)
        return Beam(
            cos(fromAngle) * innerAt(fromY), fromY, sin(fromAngle) * innerAt(fromY),
            cos(toAngle) * innerAt(toY), toY, sin(toAngle) * innerAt(toY),
            thickness,
        )
    }

    private fun rawJag(angle: Double): Double = RIM_HARMONICS.indices.sumOf { harmonic ->
        RIM_WEIGHTS[harmonic] * cos(RIM_HARMONICS[harmonic] * angle + rimPhases[harmonic])
    }

    /** How high the broken rim stands at [angle]: the summit at [topY], and the rest jagged down from it. */
    private fun rimAt(angle: Double): Double = topY - (rawJag(angle) - lowestJag) / (2.0 * RIM_WEIGHTS.sum()) * span * JAG

    private fun shareUp(y: Int): Double = ((y - floorY).toDouble() / span).coerceIn(0.0, 1.0)

    override fun resized(factor: Double, pivotY: Int) =
        copy(floorY = scaledAbout(floorY, factor, pivotY), topY = scaledAbout(topY, factor, pivotY))

    companion object {
        private const val FULL_TURN = 2.0 * PI
        private const val SHORTEST_SPAN = 24

        // Proportions, against the height from the floor to the top.
        private const val RADIUS_OF_SPAN = 0.3
        private const val WALL_OF_RADIUS = 0.17
        private const val BARK_OF_RADIUS = 0.05
        private const val THINNEST_WALL = 3.0
        private const val THINNEST_BARK = 1.0
        private const val FOOTING_DEPTH = 4
        private const val SWAMP_SHARE = 0.17
        private const val FOREST_SHARE = 0.62

        // The trunk: narrowing upward, flared at the foot into fins, ridged, and leaning a little.
        private const val TAPER = 0.15
        private const val FLARE = 0.35
        private const val FLARE_REACH = 0.08
        private const val INNER_FLARE = 0.5
        private const val FIN = 0.45
        private const val FIN_REACH = 0.12
        private const val FEWEST_FINS = 5
        private const val FIN_CHOICES = 3
        private const val RIDGE = 0.025
        private const val RIDGE_SPACING = 7.0
        private const val FEWEST_RIDGES = 8
        private const val TWIST = 1.5
        private const val TWIST_LENGTH = 23.0
        private const val LEAN = 0.06
        private const val HALFWAY_LEAN = 0.25

        // The broken top.
        private val RIM_HARMONICS = listOf(2.0, 3.0, 5.0)
        private val RIM_WEIGHTS = listOf(0.5, 0.3, 0.2)
        private const val JAG = 0.2
        private const val RIM_SAMPLES = 360

        // Rot holes through the wall, and one at the foot that lets the sea in.
        private const val FEWEST_HOLES = 6
        private const val HOLE_CHOICES = 5
        private const val LEAST_HOLE_ARC = 0.12
        private const val MOST_HOLE_ARC = 0.3
        private const val LEAST_HOLE_HEIGHT = 0.12
        private const val MOST_HOLE_HEIGHT = 0.3
        private const val HOLE_CLEARANCE = 0.25
        private const val BASE_OPENING_ARC = 0.3
        private const val BASE_OPENING_HEIGHT = 6.0
        private const val BASE_OPENING_SHARE = 0.06

        // The hollow's floor.
        private const val MOUND_FIRST = 0.6
        private const val MOUND_SECOND = 0.6
        private const val MOUND_RINGS = 7.0
        private const val MOUND_HEIGHT = 7.0
        private const val BANK_WIDTH = 3.0
        private const val INNER_FACE = 1.0

        // The shelves round the wall, open down the middle so light reaches the ones below.
        private const val CLOSEST_SHELVES = 12.0
        private const val FARTHEST_SHELVES = 26.0
        private const val SHELF_SPACING = 0.15
        private const val SHELF_SPACING_SWING = 0.15
        private const val FIRST_SHELF = 0.6
        private const val LAST_SHELF_CLEARANCE = 0.5
        private const val THINNEST_SHELF = 2.0
        private const val SHELF_THICKNESS = 0.025
        private const val BRACKET = 0.8
        private const val SHELF_LEAST = 0.25
        private const val SHELF_MORE = 0.45
        private const val SHELF_FIRST = 0.6
        private const val SHELF_SECOND = 0.4
        private const val SHELF_GAP = 0.15
        private const val SHELF_BELOW_RIM = 2

        // Branches across the hollow.
        private const val CROSSING_CHOICES = 2
        private const val CROSSING_SKEW = 0.7
        private const val CROSSING_CLEARANCE = 2.0
        private const val CROSSING_RISE = 0.3
        private const val CROSSING_ROOTED = 0.4
        private const val CROSSING_OF_RADIUS = 0.05
        private const val THINNEST_CROSSING = 1.6

        // Roots and dead branches outside.
        private const val BRANCH_OF_RADIUS = 0.09
        private const val THINNEST_BRANCH = 2.0
        private const val ROOT_HEIGHT = 0.1
        private const val ROOT_DEPTH = 3.0
        private const val LEAST_ROOT_REACH = 1.6
        private const val MOST_ROOT_REACH = 2.0
        private const val FEWEST_STUBS = 3
        private const val STUB_CHOICES = 3
        private const val LOWEST_STUB = 0.55
        private const val HIGHEST_STUB = 0.85
        private const val STUB_BELOW_RIM = 6.0
        private const val LEAST_STUB_RISE = 0.35
        private const val MOST_STUB_RISE = 0.8
        private const val SHORTEST_STUB = 0.5
        private const val LONGEST_STUB = 1.0
        private const val STUB_ROOTED = 0.7

        /** How far round the trunk the deadwood's biome reaches, against its radius, and from how far over its floor. */
        private const val CROWN_REACH = 1.3
        private const val OVER_THE_SHALLOWS = 7
        private const val REACH_SLACK = 2.0

        val CODEC: MapCodec<HollowTree> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.LONG.fieldOf("seed").forGetter(HollowTree::seed),
                Codec.INT.fieldOf("floor_y").forGetter(HollowTree::floorY),
                Codec.INT.fieldOf("top_y").forGetter(HollowTree::topY),
                Part.CODEC.fieldOf("part").forGetter(HollowTree::part),
            ).apply(instance, ::HollowTree)
        }
    }
}

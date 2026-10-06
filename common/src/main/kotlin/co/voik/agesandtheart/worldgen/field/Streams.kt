package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A stream down each of some of the shapes [placement] lays out over [ground]: it rises at the highest
 * ground near the shape's origin and runs downhill, a step at a time to the lowest ground beside it, until
 * it steps off the edge (Jonah, 2026-10-06: "source the river's headwaters at a high spot on the island and
 * then have it gently follow the terrain to the edge, maybe cutting just one or two blocks in for a bed").
 *
 * **Two answers from one course**, as [Drainage] gives a river country's rock and its water: with [water]
 * false the field is the bed cut out of the ground, [BED_DEPTH] deep under each column's own top so it
 * follows every slope; with [water] true it is the stream itself, one block deep in that bed. The water steps
 * down with the ground and pours off where the course ends.
 *
 * **A course is traced once per shape and remembered**, from the ground field rather than from blocks, so it
 * is the same course whichever chunk asks first. Tracing reads a few hundred columns of [ground]; every
 * column after that is a distance to a polyline.
 */
data class Streams(
    val ground: TerrainField,
    val placement: Placement,
    val seed: Long,
    /** How far from a shape's origin its headwaters are looked for. */
    val searchRadius: Double,
    /** How far a stream may run, in steps, before it stops wherever it is. */
    val longestCourse: Int,
    /** The share of shapes that have a stream at all. */
    val share: Double,
    val water: Boolean,
) : TerrainField {
    override val kind = FieldKind.STREAMS

    override val horizontalReach = Double.POSITIVE_INFINITY

    private val random = XoroshiroRandomSource(seed).forkPositional()

    private val reach = searchRadius + longestCourse * STEP

    /** Every course traced so far, keyed by its shape's origin; an absent value is a shape with none. */
    private val courses = ConcurrentHashMap<Long, Course>()

    /** The points a stream passes through, each at the ground's top there. */
    private class Course(val xs: IntArray, val zs: IntArray) {
        val isEmpty: Boolean get() = xs.isEmpty()

        // A box round the course, so the columns nowhere near it are turned away without measuring.
        private val reach = HALF_WIDTH.toInt() + 1
        private val minX = (xs.minOrNull() ?: 0) - reach
        private val maxX = (xs.maxOrNull() ?: 0) + reach
        private val minZ = (zs.minOrNull() ?: 0) - reach
        private val maxZ = (zs.maxOrNull() ?: 0) + reach

        fun nearTo(x: Int, z: Int): Boolean {
            if (x < minX || x > maxX || z < minZ || z > maxZ) return false
            for (index in xs.indices) {
                if (hypot((x - xs[index]).toDouble(), (z - zs[index]).toDouble()) <= HALF_WIDTH) return true
            }
            return false
        }
    }

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        var onACourse = false
        placement.forEachInstanceNear(worldX, worldZ, reach, random) { originX, originZ, instanceRandom ->
            if (onACourse) return@forEachInstanceNear
            val course = courses.computeIfAbsent(key(originX, originZ)) {
                if (instanceRandom.nextDouble() < share) traced(originX, originZ) else NO_COURSE
            }
            if (!course.isEmpty && course.nearTo(worldX, worldZ)) onACourse = true
        }
        if (!onACourse) return Spans.EMPTY
        val column = ground.columnSpans(worldX, worldZ)
        val top = column.highestSolidY ?: return Spans.EMPTY
        // Only where there is ground under the bed: a course crossing a thin overhang would cut straight
        // through it and pour its water out of the bottom.
        val waterAt = top - BED_DEPTH + 1
        val floored = column.contains(waterAt - 1) && column.contains(waterAt - 2)
        if (!floored) return Spans.EMPTY
        return if (water) Spans.of(waterAt, waterAt) else Spans.of(waterAt, Spans.HIGHEST_Y)
    }

    /** The ground's top in a column, or null where there is no ground. */
    private fun topAt(x: Int, z: Int): Int? = ground.columnSpans(x, z).highestSolidY

    /**
     * A stream down the shape at ([originX], [originZ]): from its highest inland ground within [searchRadius],
     * each step to the lowest ground [STEP] away that it has not yet stood on, until a step finds no ground.
     */
    private fun traced(originX: Int, originZ: Int): Course {
        var startX = originX
        var startZ = originZ
        var highest = Int.MIN_VALUE
        val searchSteps = (searchRadius / SEARCH_STRIDE).toInt()
        for (stepX in -searchSteps..searchSteps) {
            for (stepZ in -searchSteps..searchSteps) {
                val x = originX + stepX * SEARCH_STRIDE
                val z = originZ + stepZ * SEARCH_STRIDE
                val top = topAt(x, z) ?: continue
                // Inland, so the headwaters are not the high lip of a rim that a first step falls off.
                val inland = topAt(x + INLAND, z) != null && topAt(x - INLAND, z) != null &&
                    topAt(x, z + INLAND) != null && topAt(x, z - INLAND) != null
                if (!inland) continue
                if (top > highest) {
                    highest = top
                    startX = x
                    startZ = z
                }
            }
        }
        if (highest == Int.MIN_VALUE) return NO_COURSE
        val xs = ArrayList<Int>()
        val zs = ArrayList<Int>()
        val visited = HashSet<Long>()
        // Its own, so two streams do not wander alike.
        val wander = XoroshiroRandomSource(seed xor key(originX, originZ))
        // **Across the island, not off its nearest edge** (Jonah, 2026-10-06: "as long as possible… across the
        // centre and off the opposing edge"): a point well past the far side, roughly through the middle from
        // where the stream rose — swung up to [MOST_SWING] to one side or the other, since a river a little
        // off the middle is as good (Jonah). Rising at the middle itself, it heads off whichever way it draws.
        val awayBearing = if (hypot((originX - startX).toDouble(), (originZ - startZ).toDouble()) >= SEARCH_STRIDE) {
            atan2((originZ - startZ).toDouble(), (originX - startX).toDouble())
        } else {
            wander.nextDouble() * 2.0 * Math.PI
        }
        val bearing = awayBearing + (wander.nextDouble() * 2.0 - 1.0) * MOST_SWING
        val farX = originX + cos(bearing) * FAR_SIDE
        val farZ = originZ + sin(bearing) * FAR_SIDE
        var x = startX
        var z = startZ
        var standing = highest
        var heading: Pair<Int, Int>? = null
        for (step in 0..<longestCourse) {
            xs += x
            zs += z
            visited += key(x, z)
            var next: Pair<Int, Int>? = null
            var nextTop = standing
            var nextHeading: Pair<Int, Int>? = null
            var best = Double.MAX_VALUE
            for ((dx, dz) in NEIGHBOURS) {
                val nextX = x + dx * STEP
                val nextZ = z + dz * STEP
                if (key(nextX, nextZ) in visited) continue
                // Off the edge is a step down like any other, a little better than level, so a stream leaves
                // where the edge is ahead of it and passes it by where it is only beside it.
                val top = topAt(nextX, nextZ)
                val height = top?.toDouble() ?: (standing - EDGE_PULL)
                // Downhill first: a block of fall outweighs everything below. On the level it keeps roughly to
                // its heading, makes for the far side and wanders a little, as a stream does.
                val turning = heading?.let { (hx, hz) -> turnBetween(hx, hz, dx, dz) } ?: 0.0
                val astray = turnBetween((farX - x).toInt(), (farZ - z).toInt(), dx, dz).takeUnless { it.isNaN() } ?: 0.0
                val score = height + turning * TURN_COST + astray * TOWARDS_THE_FAR_SIDE + wander.nextDouble() * WANDER
                if (score < best) {
                    best = score
                    next = if (top == null) null else nextX to nextZ
                    nextTop = top ?: Int.MIN_VALUE
                    nextHeading = dx to dz
                }
            }
            // Gone over the edge, or nowhere left to go: the course ends on the last ground it stood on.
            val going = next ?: break
            x = going.first
            z = going.second
            standing = nextTop
            heading = nextHeading
        }
        return Course(xs.toIntArray(), zs.toIntArray())
    }

    override fun resized(factor: Double, pivotY: Int) = copy(
        ground = ground.resized(factor, pivotY),
        placement = placement.resized(factor),
        searchRadius = searchRadius * factor,
        longestCourse = (longestCourse * factor).toInt(),
    )

    companion object {
        /** How far a course moves in one step: a gentle stream turns over a few blocks, not every one. */
        const val STEP = 2

        /** How far either side of its course a stream reaches: about four blocks across (Jonah, 2026-10-06). */
        private const val HALF_WIDTH = 2.0

        /** How deep the bed is cut under the ground, the water standing in the lower block of it. */
        private const val BED_DEPTH = 2

        private const val SEARCH_STRIDE = 4

        /**
         * What a full turn about costs a step, what heading away from the far side does, and how much a step
         * may wander — together a block at most, so any fall at all decides before they do.
         */
        private const val TURN_COST = 0.2
        private const val TOWARDS_THE_FAR_SIDE = 0.6
        private const val WANDER = 0.2

        /** How much better than level a step off the edge counts. */
        private const val EDGE_PULL = 0.4

        /** How far past the middle the point a stream makes for lies — well beyond any island. */
        private const val FAR_SIDE = 200.0

        /** How far that point may swing to either side of straight across, in radians: 35 degrees. */
        private const val MOST_SWING = 0.61

        /** How far apart two headings point: 0 for the same way, 1 for opposite. */
        private fun turnBetween(fromX: Int, fromZ: Int, toX: Int, toZ: Int): Double {
            val cosine = (fromX * toX + fromZ * toZ) / (hypot(fromX.toDouble(), fromZ.toDouble()) * hypot(toX.toDouble(), toZ.toDouble()))
            return (1.0 - cosine) / 2.0
        }

        /** How far a stream's headwaters must have ground on every side. */
        private const val INLAND = 10

        private val NO_COURSE = Course(IntArray(0), IntArray(0))

        private val NEIGHBOURS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, 1 to -1, -1 to 1, -1 to -1)

        private fun key(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)

        fun codec(self: Codec<TerrainField>): MapCodec<Streams> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                self.fieldOf("ground").forGetter(Streams::ground),
                Placement.CODEC.fieldOf("placement").forGetter(Streams::placement),
                Codec.LONG.fieldOf("seed").forGetter(Streams::seed),
                Codec.DOUBLE.fieldOf("search_radius").forGetter(Streams::searchRadius),
                Codec.INT.fieldOf("longest_course").forGetter(Streams::longestCourse),
                Codec.DOUBLE.fieldOf("share").forGetter(Streams::share),
                Codec.BOOL.fieldOf("water").forGetter(Streams::water),
            ).apply(instance, ::Streams)
        }
    }
}

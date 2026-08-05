package co.voik.agesandtheart.worldgen.field

import co.voik.agesandtheart.worldgen.NEEDS_LANDFORMS
import co.voik.agesandtheart.worldgen.CliffField
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import kotlin.math.PI
import kotlin.math.abs

/**
 * Properties of a world that is two tables and one cliff.
 *
 * What these ask is mostly "is it still two tables and one cliff" — that the face stays a face rather than
 * spreading into a hillside, that the sea reaches one side and not the other, and that the coast is a
 * coast rather than a ruled line. Whether it *looks* right is `./gradlew :common:preview --args=cliffs`,
 * and there the plan view is the one to read: a step has no profile worth looking at.
 */
@Tags(NEEDS_LANDFORMS)
class CliffCheck : FunSpec({

    val world = CliffField.world(bearing = NORTH_SOUTH)

    fun topAt(worldX: Int, worldZ: Int) = world.columnSpans(worldX, worldZ).highestSolidY

    /** The height chosen against a render distance, which is the whole reason the preset has a number. */
    test("the plateau stands the drop it claims over the sea") {
        check(CliffField.PLATEAU_Y - CliffField.SEA_LEVEL == CliffField.DROP_TO_THE_SEA) {
            "the plateau at ${CliffField.PLATEAU_Y} is not ${CliffField.DROP_TO_THE_SEA} over the sea"
        }
    }

    /**
     * **The seabed floods and the tableland does not**, which is what makes this an ocean and a plateau
     * rather than two plateaus. Asked of the two tables rather than of every column: the face between them
     * climbs in ledges, and one of those may well stand awash — a wave-cut platform is a fine thing to
     * have, and demanding no column anywhere sit near the waterline would forbid it.
     */
    test("the sea reaches the low table and no part of the high one") {
        val tops = sample { worldX, worldZ -> topAt(worldX, worldZ) }
        val lowTable = tops.filter { it <= CliffField.SEABED_Y + TABLE_MARGIN }
        val highTable = tops.filter { it >= CliffField.PLATEAU_Y - TABLE_MARGIN }
        check(lowTable.isNotEmpty() && highTable.isNotEmpty()) { "the world came out all one thing" }
        check(lowTable.max() < CliffField.SEA_LEVEL) { "the seabed broke the surface at ${lowTable.max()}" }
        check(highTable.min() > CliffField.SEA_LEVEL) { "the tableland dipped under the sea at ${highTable.min()}" }
    }

    /**
     * **The face has to stay a face.** A step blended over enough blocks stops being a cliff and becomes a
     * hillside, and nothing about the render would say so — the plan view shades by height either way. The
     * ledges are inside this bound on purpose: a few blocks of tread is a bench on a cliff, and a hundred
     * would be a terrace on a hill.
     */
    test("the cliff is sheer rather than a slope") {
        val tops = sample { worldX, worldZ -> topAt(worldX, worldZ) }
        val low = CliffField.SEABED_Y + TABLE_MARGIN
        val high = CliffField.PLATEAU_Y - TABLE_MARGIN
        val onTheFace = tops.count { it in low..high }
        val share = onTheFace.toDouble() / tops.size
        check(share < WIDEST_FACE_SHARE) { "$onTheFace of ${tops.size} columns stood partway up the face" }
    }

    /**
     * And it has to stay *reachable* by the weather. `Weathered` works downward from a column's own top,
     * so it can only bite ground something stands on — a face crossed in one step has almost no column
     * topping out on it, and weathering such a cliff measurably does nothing. The ledges are what give it
     * treads, so this asserts they exist rather than that they are pretty.
     */
    test("the face has ledges for the weather to reach") {
        val bare = CliffField.bareWorld(NORTH_SOUTH)
        val tops = sample { worldX, worldZ -> bare.columnSpans(worldX, worldZ).highestSolidY }
        val low = CliffField.SEABED_Y + TABLE_MARGIN
        val high = CliffField.PLATEAU_Y - TABLE_MARGIN
        val onTheFace = tops.count { it in low..high }
        check(onTheFace > 0) { "no column anywhere topped out part-way down the face" }

        val weathered = sample { worldX, worldZ -> topAt(worldX, worldZ) }
        val worn = weathered.zip(tops).count { (after, before) -> after != before }
        check(worn > tops.size / LEAST_WORN_IN) { "the weather changed only $worn of ${tops.size} columns" }
    }

    /** A coast ruled straight across the world would read as a wall someone built. */
    test("the coast wanders rather than running straight") {
        val halfway = (CliffField.SEABED_Y + CliffField.PLATEAU_Y) / 2
        val coast = (-500..500 step 25).mapNotNull { worldZ ->
            (-600..600 step 2).firstOrNull { worldX -> (topAt(worldX, worldZ) ?: 0) > halfway }
        }
        check(coast.size > 20) { "the coast was only found at ${coast.size} of the sampled cross-sections" }
        val wander = coast.max() - coast.min()
        check(wander > LEAST_WANDER) { "the coast moved only $wander blocks across the whole sample" }
    }

    /**
     * Two tables, not two heightmaps: the shape is one run from the world's floor in every column.
     *
     * Asked of the **bare** world. The weather is allowed to leave a column in pieces — that is what an
     * overhang is — and demanding otherwise here would forbid the one thing erosion adds that a heightmap
     * cannot express.
     */
    test("the shape is solid from the floor up") {
        val bare = CliffField.bareWorld(NORTH_SOUTH)
        val runs = sample { worldX, worldZ -> bare.columnSpans(worldX, worldZ).ranges.size }
        check(runs.all { it == 1 }) { "some column of the bare shape came out in more than one piece" }
    }

    test("resizing scales the lengths and keeps the bearing") {
        val full = CliffField.bareWorld(NORTH_SOUTH) as Escarpment
        val small = full.resized(HALF, pivotY = full.lowY)
        check(small.faceWidth == full.faceWidth * HALF) { "the face came out ${small.faceWidth}" }
        check(small.roughness == full.roughness * HALF) { "the roughness came out ${small.roughness}" }
        check(small.bearing == full.bearing) { "the bearing turned to ${small.bearing}" }
        check(small.lowY == full.lowY) { "the low side moved to ${small.lowY}" }
        // Within a block: the scaling rounds each level rather than the distance between them.
        val drop = full.highY - full.lowY
        val scaled = small.highY - small.lowY
        check(abs(scaled - drop * HALF) <= 1.0) { "a drop of $drop halved to $scaled" }
    }

    /** The bearing is the direction the cliff *runs*, so turning it must turn the world's division too. */
    test("a bearing turns the cliff rather than the world") {
        val eastWest = CliffField.world(EAST_WEST)
        val alongTheFace = (-400..400 step 25).map { worldX ->
            eastWest.columnSpans(worldX, FAR_ALONG).highestSolidY ?: 0
        }
        val allOneSide = alongTheFace.all { it > CliffField.SEA_LEVEL } || alongTheFace.all { it < CliffField.SEA_LEVEL }
        check(allOneSide) { "an east-west cliff still divided the world along x: $alongTheFace" }
    }

    test("the world round-trips through its codec") {
        val written = CliffField.world(DIAGONAL)
        val encoded = TerrainField.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, written)
            .getOrThrow { failure -> error("the cliff would not encode: $failure") }
        val read = TerrainField.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, encoded)
            .getOrThrow { failure -> error("the cliff would not read back: $failure") }
        check(read == written) { "read back as a different world: $read" }
    }

    test("a diagonal cliff is neither of the axis-aligned ones") {
        val diagonal = CliffField.world(DIAGONAL)
        check(bearingAt(DIAGONAL) !in listOf(0.0, PI / 2)) { "the diagonal bearing was an axis" }
        val corner = diagonal.columnSpans(400, 400).highestSolidY
        val opposite = diagonal.columnSpans(-400, -400).highestSolidY
        check(corner != opposite) { "opposite corners of a diagonal cliff both stood at $corner" }
    }
}) {
    private companion object {
        const val HALF = 0.5

        /** Far enough along an east-west cliff to be clear of the origin the meander is measured from. */
        const val FAR_ALONG = 300

        /**
         * How far off its own level a column may sit and still count as part of that table. **Smaller
         * than the seabed's freeboard**, or the low table's own definition reaches over the waterline and
         * the test asks a question it has made unanswerable.
         */
        const val TABLE_MARGIN = 20

        /** A face thirty blocks across, sampled over a thousand, should stay a small fraction of them. */
        const val WIDEST_FACE_SHARE = 0.12

        /** One column in this many, at least, has to be touched — the weather is not decoration. */
        const val LEAST_WORN_IN = 40

        const val LEAST_WANDER = 100

        /** A coarse lattice over a window wide enough to hold several sweeps of coast. */
        fun <T> sample(read: (Int, Int) -> T?): List<T> =
            (-500..500 step 13).flatMap { worldZ ->
                (-500..500 step 7).mapNotNull { worldX -> read(worldX, worldZ) }
            }
    }
}
